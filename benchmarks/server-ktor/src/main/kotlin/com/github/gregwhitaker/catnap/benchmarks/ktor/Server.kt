package com.github.gregwhitaker.catnap.benchmarks.ktor

import com.github.gregwhitaker.catnap.benchmarks.stream.BenchmarkControl
import com.github.gregwhitaker.catnap.http.stream.StreamPayload
import com.github.gregwhitaker.catnap.ktor.Catnap
import com.github.gregwhitaker.catnap.ktor.jsonStreamEncoder
import com.github.gregwhitaker.catnap.ktor.respondNdjson
import com.github.gregwhitaker.catnap.ktor.respondSse
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import io.ktor.sse.TypedServerSentEvent
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.jdk9.asFlow
import kotlinx.serialization.json.*

private fun jsonValue(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is Map<*, *> ->
            JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) })
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

fun main() {
    embeddedServer(CIO, host = "127.0.0.1", port = System.getProperty("benchmark.port").toInt()) {
            val configuredJson = Json { encodeDefaults = true }
            val value = jsonValue(BenchmarkControl.payload())
            fun source(rate: Int) = BenchmarkControl.source(rate).asFlow().buffer(0).map { value }
            fun ApplicationCall.rate() = request.queryParameters["rate"]?.toInt() ?: 0
            install(ContentNegotiation) { json(configuredJson) }
            install(SSE)
            routing {
                get("/benchmark/metrics") {
                    call.respond(
                        jsonValue(
                            BenchmarkControl.metrics(
                                call.request.queryParameters["collect"] == "true"
                            )
                        )
                    )
                }
                get("/benchmark/pause") {
                    call.respond(
                        jsonValue(
                            BenchmarkControl.pause(call.request.queryParameters["value"] == "true")
                        )
                    )
                }
                get("/benchmark/native/ndjson") {
                    val records = source(call.rate())
                    call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                        records.collect {
                            write(configuredJson.encodeToString(JsonElement.serializer(), it))
                            write("\n")
                            flush()
                        }
                    }
                }
                sse("/benchmark/native/sse") {
                    source(call.rate()).collect {
                        send(
                            ServerSentEvent(
                                data = configuredJson.encodeToString(JsonElement.serializer(), it),
                                event = "sample",
                                id = "1",
                                retry = 1000,
                                comments = "benchmark",
                            )
                        )
                    }
                }
                route("/benchmark/catnap") {
                    install(Catnap)
                    get("/ndjson") {
                        call.respondNdjson(
                            source(call.rate()),
                            call.jsonStreamEncoder(configuredJson, JsonElement.serializer()),
                        )
                    }
                    get("/sse") {
                        call.respondSse(
                            source(call.rate()).map {
                                TypedServerSentEvent(
                                    data = StreamPayload.json(it),
                                    event = "sample",
                                    id = "1",
                                    retry = 1000,
                                    comments = "benchmark",
                                )
                            },
                            call.jsonStreamEncoder(configuredJson, JsonElement.serializer()),
                        )
                    }
                }
            }
        }
        .start(wait = true)
}
