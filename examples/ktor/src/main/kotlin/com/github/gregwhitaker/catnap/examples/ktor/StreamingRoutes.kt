package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.http.stream.StreamPayload
import com.github.gregwhitaker.catnap.ktor.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.sse.ServerSentEvent
import io.ktor.sse.TypedServerSentEvent
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.*

/**
 * Test-visible lifecycle state; each connection owns its source and never shares a query or
 * encoder.
 */
class StreamProbe {
    val release = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val emitted = AtomicInteger()

    companion object {
        val connections = ConcurrentHashMap<String, StreamProbe>()
    }
}

private fun streamQuote() = Envelope(ProjectionQuote("ACME", Money(BigDecimal("12.50"))))

fun Route.streamingRoutes() {
    route("/catnap/stream") {
        get("/ndjson") {
            call.representationHeader("ETag", "\"original\"")
            call.response.headers.append("X-Application", "preserved")
            call.respondNdjson(
                flowOf(streamQuote(), streamQuote()),
                call.jsonStreamEncoder(
                    projectionJson,
                    Envelope.serializer(ProjectionQuote.serializer()),
                ),
            )
        }
        get("/shapes") {
            val values =
                listOf("[{\"id\":1,\"other\":2},{\"other\":3},null,7]", "null", "42", "[]", "{}")
                    .map(Json::parseToJsonElement)
            call.respondNdjson(
                values.asFlow(),
                call.jsonStreamEncoder(projectionJson, JsonElement.serializer()),
            )
        }
        get("/empty") {
            call.respondNdjson(
                emptyFlow<JsonElement>(),
                call.jsonStreamEncoder(projectionJson, JsonElement.serializer()),
            )
        }
        get("/send") {
            call.representationHeader("ETag", "\"original\"")
            call.respondSse(
                call.jsonStreamEncoder(
                    projectionJson,
                    Envelope.serializer(ProjectionQuote.serializer()),
                )
            ) {
                send(
                    TypedServerSentEvent(
                        StreamPayload.json(streamQuote()),
                        event = "quote",
                        id = "42",
                    )
                )
                send(TypedServerSentEvent(comments = "heartbeat"))
            }
        }
        get("/sse") {
            val serializer = Envelope.serializer(ProjectionQuote.serializer()).nullable
            val events =
                flowOf(
                    TypedServerSentEvent(
                        StreamPayload.json<Envelope<ProjectionQuote>?>(streamQuote()),
                        event = "quote",
                        id = "42",
                        retry = 1500,
                        comments = "first\nsecond",
                    ),
                    TypedServerSentEvent(
                        StreamPayload.json<Envelope<ProjectionQuote>?>(null),
                        id = "43",
                    ),
                    TypedServerSentEvent(
                        StreamPayload.text<Envelope<ProjectionQuote>?>(
                            "{\"private\":true}\nplain text"
                        ),
                        event = "text",
                        id = "44",
                    ),
                    TypedServerSentEvent<StreamPayload<Envelope<ProjectionQuote>?>>(
                        id = "",
                        retry = 0,
                        comments = "heartbeat",
                    ),
                )
            call.respondSse(events, call.jsonStreamEncoder(projectionJson, serializer))
        }
        get("/encoded") {
            val mode = call.request.queryParameters["mode"]
            val raw =
                when (mode) {
                    "blank" -> "\n"
                    "bare-cr" -> "{}\r{}\n"
                    "tail" -> "{}"
                    "malformed" -> "{\"private\":\"never leaked\",\n"
                    "oversized" -> "{\"private\":\"${"x".repeat(10_000)}\"}\n"
                    "depth" -> "[".repeat(132) + "0" + "]".repeat(132) + "\n"
                    else ->
                        "{\"label\":\"é😀\",\"other\":1}\r\n{\"label\":\"second\",\"other\":2}\n"
                }
            val bytes =
                if (mode == "utf8") byteArrayOf(0xc3.toByte(), 0x28, 10) else raw.toByteArray()
            // The first input chunks split every UTF-8 codepoint; the last chunk contains several
            // rows.
            val chunks = flow {
                val boundary = minOf(bytes.size, 20)
                for (index in 0 until boundary) emit(byteArrayOf(bytes[index]))
                if (boundary < bytes.size) emit(bytes.copyOfRange(boundary, bytes.size))
            }
            call.respondEncodedNdjson(
                chunks,
                call.jsonStreamEncoder(projectionJson, JsonElement.serializer()),
            )
        }
        get("/invalid-event") {
            val mode = call.request.queryParameters["mode"]
            val payload: StreamPayload<JsonElement> =
                when (mode) {
                    "metadata" -> StreamPayload.json(JsonNull)
                    "oversized" -> StreamPayload.text("x".repeat(10_000))
                    else -> StreamPayload.encodedJson("{\"private\":\"never leaked\",")
                }
            call.respondSse(
                flowOf(
                    TypedServerSentEvent(
                        payload,
                        comments = if (mode == "metadata") "x".repeat(10_000) else null,
                    )
                ),
                call.jsonStreamEncoder(projectionJson, JsonElement.serializer()),
            )
        }
        get("/lifecycle/{protocol}") {
            val probe =
                call.request.queryParameters["key"]?.let(StreamProbe.connections::get)
                    ?: StreamProbe()
            val mode = call.request.queryParameters["mode"] ?: "gated"
            val source = flow {
                try {
                    if (mode == "before") throw IOException("Source failed before emission")
                    var index = 0
                    do {
                        currentCoroutineContext().ensureActive()
                        probe.emitted.incrementAndGet()
                        emit(
                            buildJsonObject {
                                put("id", index++)
                                put(
                                    "other",
                                    if (mode == "limit" && index == 2) "x".repeat(10_000)
                                    else if (mode == "infinite") "x".repeat(3800) else "private",
                                )
                            }
                        )
                        if (mode != "infinite") {
                            probe.release.await()
                            if (mode == "after") throw IOException("Source failed after emission")
                        }
                    } while (mode == "infinite" || index < 2)
                } finally {
                    probe.finished.complete(Unit)
                }
            }
            val encoder =
                call.jsonStreamEncoder<JsonElement> { value, output ->
                    val second = value.jsonObject["id"].toString() == "1"
                    if (mode == "serialize-before" || (mode == "serialize" && second))
                        throw IOException("Custom serializer: never leaked")
                    val text =
                        if (mode == "parse" && second) "{\"private\":\"never leaked\","
                        else if (mode == "depth" && second) "[".repeat(132) + "0" + "]".repeat(132)
                        else projectionJson.encodeToString(JsonElement.serializer(), value)
                    output.write(text.toByteArray())
                }
            if (call.parameters["protocol"] == "sse") {
                call.respondSse(
                    source.map {
                        TypedServerSentEvent(
                            StreamPayload.json(it),
                            id = it.jsonObject["id"].toString(),
                            event = "item",
                        )
                    },
                    encoder,
                    onError =
                        if (call.request.queryParameters["recover"] == "true") {
                            { _: Throwable ->
                                ServerSentEvent(
                                    "temporary failure",
                                    event = "application-error",
                                    id = "failure",
                                )
                            }
                        } else null,
                )
            } else call.respondNdjson(source, encoder)
        }
    }
}
