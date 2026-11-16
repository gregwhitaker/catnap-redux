package com.github.gregwhitaker.catnap.ktor

import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder
import com.github.gregwhitaker.catnap.http.stream.StreamPayload
import com.github.gregwhitaker.catnap.streams.NdjsonFramer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import io.ktor.sse.TypedServerSentEvent
import io.ktor.utils.io.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/**
 * Captures the already-bound query and the application's complete native serialization operation.
 */
fun <T> ApplicationCall.jsonStreamEncoder(
    serializer: JsonStreamEncoder.Serializer<T>
): JsonStreamEncoder<T> {
    val request = projectionRequest()
    return JsonStreamEncoder(
        serializer,
        { FiniteJson.project(it, request, Charsets.UTF_8) },
        request.active(),
        request.settings().maxPayloadBytes(),
    )
}

/** Preserve the supplied Json configuration and explicit generic/contextual serializer. */
@OptIn(ExperimentalSerializationApi::class)
fun <T> ApplicationCall.jsonStreamEncoder(
    json: Json,
    serializer: SerializationStrategy<T>,
): JsonStreamEncoder<T> = jsonStreamEncoder { value, output ->
    json.encodeToStream(serializer, value, output)
}

/** Emits independent compact JSON records, flushing each; no aggregate document is collected. */
suspend fun <T> ApplicationCall.respondNdjson(source: Flow<T>, encoder: JsonStreamEncoder<T>) {
    sendNdjsonBytes(source.map { encoder.ndjson(it) })
}

/**
 * Accepts arbitrary encoded chunks; LF/CRLF and split UTF-8 are framed before querying each record.
 */
suspend fun <T> ApplicationCall.respondEncodedNdjson(
    source: Flow<ByteArray>,
    encoder: JsonStreamEncoder<T>,
) {
    val maximum = projectionRequest().settings().maxPayloadBytes()
    sendNdjsonBytes(
        flow {
            val framer = NdjsonFramer(maximum)
            source.collect { chunk ->
                for (byte in chunk) {
                    currentCoroutineContext().ensureActive()
                    framer.accept(byte.toInt() and 255)?.let { emit(encoder.ndjsonEncoded(it)) }
                }
            }
            framer.endOfInput()
        }
    )
}

private suspend fun ApplicationCall.sendNdjsonBytes(source: Flow<ByteArray>) = streamResponse {
    withFirst(source) { first, rest ->
        prepareProjectedStream()
        respondBytesWriter(ContentType.parse("application/x-ndjson")) {
            if (first != null) {
                writeFully(first)
                writeByte(10)
                flush()
            }
            for (record in rest) {
                writeFully(record)
                writeByte(10)
                flush()
            }
        }
    }
}

/**
 * Uses Ktor's native SSE session and framing; absent data is a control event, explicit JSON null is
 * data. An optional protocol error event is bounded but intentionally not projected. It is emitted
 * only for a failure after commitment, followed by completion; cancellation is never recovered.
 */
suspend fun <T> ApplicationCall.respondSse(
    source: Flow<TypedServerSentEvent<StreamPayload<T>>>,
    encoder: JsonStreamEncoder<T>,
    onError: ((Throwable) -> ServerSentEvent?)? = null,
) = streamResponse {
    application.plugin(SSE)
    withFirst(source.map { encodeEvent(it, encoder) }) { first, rest ->
        prepareProjectedStream()
        response.headers.appendIfAbsent(HttpHeaders.CacheControl, "no-store")
        if (request.httpVersion.startsWith("HTTP/1."))
            response.headers.appendIfAbsent(HttpHeaders.Connection, "keep-alive")
        response.headers.appendIfAbsent("X-Accel-Buffering", "no")
        respond(
            SSEServerContent(
                this,
                handle = {
                    try {
                        if (first != null) send(first)
                        for (event in rest) send(event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val error = onError?.invoke(failure) ?: throw failure
                        validateErrorEvent(
                            error,
                            call.projectionRequest().settings().maxPayloadBytes(),
                        )
                        send(error)
                    }
                },
            )
        )
    }
}

/**
 * An imperative native-session adapter; concurrent sends fail instead of creating a waiter queue.
 */
fun <T> ServerSSESession.projecting(encoder: JsonStreamEncoder<T>): CatnapSseSession<T> =
    CatnapSseSession(this, encoder)

/**
 * Opens a native imperative session after query validation and metadata adjustment. Payload
 * failures terminate the committed stream; use the Flow overload to preflight its first payload.
 */
suspend fun <T> ApplicationCall.respondSse(
    encoder: JsonStreamEncoder<T>,
    handle: suspend CatnapSseSession<T>.() -> Unit,
) = streamResponse {
    application.plugin(SSE)
    prepareProjectedStream()
    response.headers.appendIfAbsent(HttpHeaders.CacheControl, "no-store")
    if (request.httpVersion.startsWith("HTTP/1."))
        response.headers.appendIfAbsent(HttpHeaders.Connection, "keep-alive")
    response.headers.appendIfAbsent("X-Accel-Buffering", "no")
    respond(SSEServerContent(this, handle = { projecting(encoder).handle() }))
}

class CatnapSseSession<T>
internal constructor(
    private val session: ServerSSESession,
    private val encoder: JsonStreamEncoder<T>,
) {
    private val sending = Mutex()
    @Volatile private var closed = false

    suspend fun send(event: TypedServerSentEvent<StreamPayload<T>>) {
        check(sending.tryLock()) {
            "Concurrent Catnap sends require a bounded upstream queue or a single producer"
        }
        try {
            check(!closed) { "The Catnap SSE session is closed" }
            session.send(encodeEvent(event, encoder))
        } catch (failure: Throwable) {
            closed = true
            withContext(NonCancellable) {
                try {
                    session.close()
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
            }
            if (failure is CancellationException || failure is Error) throw failure
            throw IOException("JSON stream response failed", failure)
        } finally {
            sending.unlock()
        }
    }
}

// Ktor's default error response includes the top-level exception message. Parser and application
// serializer messages can contain input data, so expose a stable message and retain the cause.
private suspend fun streamResponse(send: suspend () -> Unit) {
    try {
        send()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw IOException("JSON stream response failed", failure)
    }
}

private fun validateErrorEvent(event: ServerSentEvent, maximum: Int) {
    val bounds = JsonStreamEncoder<String>({ _, _ -> }, { it }, false, maximum)
    bounds.metadata(event.event, event.id, event.comments, event.retry?.toString())
    event.data?.let { bounds.payload(StreamPayload.text(it)) }
}

private fun <T> encodeEvent(
    event: TypedServerSentEvent<StreamPayload<T>>,
    encoder: JsonStreamEncoder<T>,
): ServerSentEvent {
    encoder.metadata(event.event, event.id, event.comments, event.retry?.toString())
    return ServerSentEvent(
        event.data?.let { encoder.payload(it).toString(Charsets.UTF_8) },
        event.event,
        event.id,
        event.retry,
        event.comments,
    )
}

/**
 * Rendezvous handoff: one item being sent plus at most one prepared item, all in the call's scope.
 */
internal suspend fun <T : Any> withFirst(
    source: Flow<T>,
    send: suspend (T?, ReceiveChannel<T>) -> Unit,
) = coroutineScope {
    val pending = Channel<T>(Channel.RENDEZVOUS)
    val producer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.collect { pending.send(it) }
                pending.close()
            } catch (failure: Throwable) {
                pending.close(failure)
            }
        }
    try {
        val first = pending.receiveCatching()
        first.exceptionOrNull()?.let { throw it }
        send(first.getOrNull(), pending)
    } finally {
        pending.cancel()
        withContext(NonCancellable) { producer.cancelAndJoin() }
    }
}
