package com.github.gregwhitaker.catnap.ktor

import com.github.gregwhitaker.catnap.http.BoundedBuffer
import com.github.gregwhitaker.catnap.http.ProjectionRequest
import com.github.gregwhitaker.catnap.kotlinx.KotlinxJson
import com.github.gregwhitaker.catnap.kotlinx.KotlinxTreeAdapter
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.asSink
import kotlinx.serialization.json.*

/** Captures one finite document without committing bytes or detaching a producer coroutine. */
internal object FiniteJson {
    private val json = KotlinxJson(Json)

    suspend fun capture(content: OutgoingContent, maximum: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        val buffer = BoundedBuffer(maximum)
        when (content) {
            is OutgoingContent.ByteArrayContent -> buffer.write(content.bytes())
            is OutgoingContent.ReadChannelContent -> {
                val source = content.readFrom()
                try {
                    val chunk = ByteArray(minOf(8192, maximum))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.readAvailable(chunk)
                        if (count < 0) break
                        buffer.write(chunk, 0, count)
                    }
                    source.closedCause?.let { throw it }
                } finally {
                    source.cancel(null)
                }
            }
            is OutgoingContent.WriteChannelContent -> {
                val channel = buffer.asSink().asByteWriteChannel()
                try {
                    content.writeTo(channel)
                    channel.flushAndClose()
                } catch (failure: Throwable) {
                    channel.cancel(failure)
                    throw failure
                }
            }
            is OutgoingContent.ContentWrapper -> return capture(content.delegate(), maximum)
            else -> throw IOException("Expected a finite JSON response body")
        }
        currentCoroutineContext().ensureActive()
        return buffer.toByteArray()
    }

    fun project(bytes: ByteArray, request: ProjectionRequest, charset: Charset): ByteArray {
        val source =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        checkDepth(source, request.settings().evaluation().maxDepth())
        val tree = json.parse(source)
        validateScalars(tree)
        val selected = request.evaluate(tree, KotlinxTreeAdapter)
        val text = json.write(selected)
        val encoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val escaped = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val end =
                if (
                    character.isHighSurrogate() &&
                        index + 1 < text.length &&
                        text[index + 1].isLowSurrogate()
                )
                    index + 2
                else index + 1
            if (character.code < 128 || encoder.canEncode(CharBuffer.wrap(text, index, end)))
                escaped.append(text, index, end)
            else
                for (position in index until end) escaped
                    .append("\\u")
                    .append(text[position].code.toString(16).padStart(4, '0'))
            index = end
        }
        val encoded = encoder.encode(CharBuffer.wrap(escaped))
        if (encoded.remaining() > request.settings().maxPayloadBytes())
            throw IOException("Projected JSON exceeds maximum payload size")
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    // Bound parser recursion before constructing the native tree. JSON strings and escapes do not
    // count.
    private fun checkDepth(source: String, maximum: Int) {
        var nesting = 0
        var quoted = false
        var escaped = false
        for (character in source) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else
                when (character) {
                    '"' -> quoted = true
                    '{',
                    '[' ->
                        if (++nesting > maximum + 1)
                            throw IOException("JSON nesting exceeds configured depth")
                    '}',
                    ']' -> nesting--
                }
        }
    }

    // JsonElement parsing retains raw scalar lexemes. Validate even fields a query will omit.
    private fun validateScalars(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::validateScalars)
            is JsonArray -> value.forEach(::validateScalars)
            else -> KotlinxTreeAdapter.copyScalar(value)
        }
    }
}
