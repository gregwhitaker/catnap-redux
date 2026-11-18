package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.ktor.Catnap
import com.github.gregwhitaker.catnap.ktor.representationHeader
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*

@Serializable data class Envelope<T>(val payload: T)

data class Money(val value: BigDecimal)

@Serializable
data class ProjectionQuote(
    val displayName: String,
    @Contextual val amount: Money,
    @Transient val internal: String = "internal",
    @Transient val ignored: String = "never",
)

/** An explicit internal response model supplies the serializer-visible fields for this endpoint. */
@Serializable
data class InternalQuote(
    val displayName: String,
    @Contextual val amount: Money,
    val internal: String,
    @Transient val ignored: String = "never",
)

@Serializable
data class PreciseValue(@Contextual val amount: BigDecimal, val unused: Boolean = true)

@Serializable data class StatusValue(val id: Int = 1, val hidden: String = "value")

private class MoneySerializer(private val currency: String) : KSerializer<Money> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) =
        encoder.encodeString("$currency:${value.value.toPlainString()}")

    override fun deserialize(decoder: Decoder): Money = error("Write-only example")
}

@OptIn(ExperimentalSerializationApi::class)
private object DecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("PreciseDecimal", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) =
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))

    override fun deserialize(decoder: Decoder): BigDecimal = error("Write-only example")
}

@OptIn(ExperimentalSerializationApi::class)
val projectionJson = Json {
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
    serializersModule = SerializersModule {
        contextual(Money::class, MoneySerializer("EUR"))
        contextual(BigDecimal::class, DecimalSerializer)
    }
}

object PublisherProbe {
    val emitted = AtomicInteger()
    @Volatile var finished = false
}

private fun quote() = Envelope(ProjectionQuote("ACME", Money(BigDecimal("12.50"))))

fun Route.projectionRoutes() {
    route("/catnap") {
        get("/quote") { call.respond(quote()) }
        get("/quote-internal") {
            call.respond(Envelope(InternalQuote("ACME", Money(BigDecimal("12.50")), "internal")))
        }
        get("/catalog") {
            call.respond(
                Envelope(
                    listOf(quote().payload, ProjectionQuote("OTHER", Money(BigDecimal("20.00"))))
                )
            )
        }
        get("/quotes") { call.respond(listOf(quote(), quote())) }
        get("/async") {
            delay(1)
            call.respond(quote())
        }
        get("/precise") {
            call.respond(PreciseValue(BigDecimal("0.12345678901234567890123456789")))
        }
        get("/status") {
            call.response.status(HttpStatusCode.Created)
            call.response.headers.append("X-Application", "preserved")
            call.representationHeader("ETag", "\"original\"")
            call.representationHeader("Content-Digest", "stale")
            call.representationHeader("Accept-Ranges", "bytes")
            call.representationHeader("Content-Length", "25")
            call.respond(StatusValue())
        }
        get("/error") {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "keep"))
        }
        get("/text") { call.respondText("unchanged", ContentType.Text.Plain) }
        get("/empty") { call.respond(HttpStatusCode.NoContent) }
        route("/disabled") {
            install(Catnap) { enabled = false }
            get { call.respond(mapOf("id" to 1, "other" to 2)) }
        }
        get("/malformed") {
            call.respondText("{\"secret\":\"never leaked\",", ContentType.Application.Json)
        }
        get("/oversized") { call.respond(mapOf("data" to "x".repeat(20_000))) }
        get("/read") {
            call.respond(
                object : OutgoingContent.ReadChannelContent() {
                    override val contentType = ContentType.Application.Json

                    override fun readFrom() =
                        ByteReadChannel("{\"id\":1,\"other\":2}".toByteArray())
                }
            )
        }
        get("/write") {
            call.respondBytesWriter(ContentType.Application.Json) {
                writeStringUtf8("{\"id\":1,\"other\":2}")
            }
        }
        get("/vendor") {
            call.respondText(
                "{\"id\":1,\"other\":2}",
                ContentType.parse("application/vnd.catnap+json"),
            )
        }
        get("/charset") {
            call.respondText(
                "{\"label\":\"\\uD83D\\uDE00\",\"other\":2}",
                ContentType.Application.Json.withCharset(Charsets.ISO_8859_1),
            )
        }
        get("/publisher") {
            PublisherProbe.emitted.set(0)
            PublisherProbe.finished = false
            call.respondBytesWriter(ContentType.Application.Json) {
                try {
                    writeStringUtf8("[")
                    repeat(10_000) { index ->
                        PublisherProbe.emitted.incrementAndGet()
                        if (index > 0) writeStringUtf8(",")
                        writeStringUtf8("{\"id\":$index,\"data\":\"${"x".repeat(1024)}\"}")
                        flush()
                    }
                    writeStringUtf8("]")
                } finally {
                    PublisherProbe.finished = true
                }
            }
        }
    }
}
