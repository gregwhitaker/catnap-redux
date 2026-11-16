package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.ktor.Catnap
import com.github.gregwhitaker.catnap.ktor.CatnapConfiguration
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.send
import io.ktor.server.sse.sse
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo
import io.ktor.utils.io.ByteReadChannel
import java.nio.charset.Charset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.serializer

@Serializable data class Envelope<T>(val payload: T)

data class Quote(
    val displayName: String,
    val amount: String,
    val internal: String,
    val ignored: String,
)

data class ProbeValue<T>(val body: T, val request: ProbeRequest)

@Serializable
private data class WireQuote(
    @SerialName("display_name") val displayName: String,
    val amount: String,
    val internal: String? = null,
)

/** A configured serializer demonstrates that the hook retains application-specific JSON rules. */
private class QuoteSerializer(private val includeInternal: Boolean) : KSerializer<Quote> {
    override val descriptor: SerialDescriptor = WireQuote.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Quote) {
        encoder.encodeSerializableValue(
            WireQuote.serializer(),
            WireQuote(
                value.displayName,
                "USD:${value.amount}",
                value.internal.takeIf { includeInternal },
            ),
        )
    }

    override fun deserialize(decoder: Decoder): Quote =
        error("The serialization probe is write-only")
}

private fun project(type: TypeInfo, value: Any): String {
    val pending = value as ProbeValue<*>
    val request = pending.request
    val json = Json {
        explicitNulls = false
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(Quote::class, QuoteSerializer(request.internalView()))
        }
    }
    // The declared Envelope<Quote> type must survive the framework's TypeInfo boundary.
    val declared = requireNotNull(type.kotlinType).arguments.single().type!!
    check(
        declared.classifier == Envelope::class &&
            declared.arguments.single().type!!.classifier == Quote::class
    )
    val serializer = json.serializersModule.serializer(declared)
    val full = json.encodeToJsonElement(serializer, pending.body!!).jsonObject
    val payload = full.getValue("payload").jsonObject
    request.verifyCompletePayload(payload.keys)
    val selected = payload[request.field()]?.let { mapOf(request.field() to it) } ?: emptyMap()
    return json.encodeToString(
        JsonObject.serializer(),
        JsonObject(mapOf("payload" to JsonObject(selected))),
    )
}

private class ProbeConverter : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        if (value !is ProbeValue<*>) return null
        return TextContent(project(typeInfo, value), contentType.withCharset(charset))
    }

    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel,
    ): Any? = null
}

fun Application.probeModule() {
    exampleModule()
}

fun Application.exampleModule(configureCatnap: CatnapConfiguration.() -> Unit = {}) {
    install(ContentNegotiation) {
        register(ContentType.Application.Json, ProbeConverter())
        json(projectionJson)
    }
    install(SSE)
    routing {
        install(Catnap, configureCatnap)
        projectionRoutes()
        streamingRoutes()
        route("/probe") {
            install(Catnap) { enabled = false }
            get("/json") {
                val request =
                    ProbeRequest.from(
                        call.request.queryParameters["fields"],
                        call.request.queryParameters["requestId"],
                        call.request.queryParameters["view"],
                    )
                call.respond(pending(request))
            }
            get("/ndjson") {
                val request =
                    ProbeRequest.from(
                        call.request.queryParameters["fields"],
                        call.request.queryParameters["requestId"],
                        call.request.queryParameters["view"],
                    )
                call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                    write(project(typeInfo<ProbeValue<Envelope<Quote>>>(), pending(request)))
                    write("\n")
                    flush()
                }
            }
            sse("/sse", serialize = ::project) {
                val request =
                    ProbeRequest.from(
                        call.request.queryParameters["fields"],
                        call.request.queryParameters["requestId"],
                        call.request.queryParameters["view"],
                    )
                send(
                    data = pending(request),
                    event = "quote",
                    id = request.requestId(),
                    retry = 1500,
                    comments = "probe-heartbeat",
                )
            }
        }
    }
}

private fun pending(request: ProbeRequest): ProbeValue<Envelope<Quote>> =
    ProbeValue(
        Envelope(Quote("${request.requestId()}:ACME", "12.50", "internal-only", "never-serialize")),
        request,
    )

fun main() {
    embeddedServer(CIO, port = 8080, module = Application::probeModule).start(wait = true)
}
