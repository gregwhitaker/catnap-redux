package com.github.gregwhitaker.catnap.ktor

import com.github.gregwhitaker.catnap.http.ProjectionRequest
import com.github.gregwhitaker.catnap.http.QueryRequestException
import com.github.gregwhitaker.catnap.http.ResponsePolicy
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private val projectionKey = AttributeKey<ProjectionRequest>("CatnapProjection")
private val pendingHeadersKey = AttributeKey<HeadersBuilder>("CatnapRepresentationHeaders")
private val streamingKey = AttributeKey<Boolean>("CatnapProjectedStream")

internal fun ApplicationCall.projectionRequest(): ProjectionRequest =
    attributes.getOrNull(projectionKey)
        ?: error("Install Catnap on this application or route before using a stream adapter")

internal fun ApplicationCall.prepareProjectedStream() {
    if (!projectionRequest().active()) return
    attributes.remove(pendingHeadersKey)
    val fixed = response.headers.allValues().names().filter(ResponsePolicy::invalidatedHeader)
    check(fixed.isEmpty()) {
        "Use representationHeader or OutgoingContent.headers for adjustable stream metadata: $fixed"
    }
    attributes.put(streamingKey, true)
}

/** Projection after native content negotiation and before compression and transport commitment. */
val Catnap =
    createRouteScopedPlugin("Catnap", "catnap", ::CatnapConfiguration) {
        val service = pluginConfig.createService()
        on(BindQuery) { call ->
            try {
                call.attributes.put(
                    projectionKey,
                    service.bind(
                        call.request.queryParameters.getAll(service.settings().parameter())
                    ),
                )
                true
            } catch (failure: QueryRequestException) {
                val problem = buildJsonObject {
                    put("type", "about:blank")
                    put("title", "Invalid partial response query")
                    put("status", 400)
                    put("code", failure.code())
                }
                call.respondText(
                    problem.toString(),
                    ContentType.parse("application/problem+json"),
                    HttpStatusCode.BadRequest,
                )
                false
            }
        }
        on(ProjectRenderedJson) { call, content ->
            if (call.attributes.getOrNull(streamingKey) == true)
                call.attributes.remove(pendingHeadersKey)
            val request = call.attributes.getOrNull(projectionKey)
            val contentType =
                content.contentType
                    ?: call.response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
            val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
            val encoding =
                content.headers[HttpHeaders.ContentEncoding]
                    ?: call.response.headers[HttpHeaders.ContentEncoding]
            if (
                request == null ||
                    !request.active() ||
                    call.request.httpMethod == HttpMethod.Head ||
                    content is OutgoingContent.NoContent ||
                    !ResponsePolicy.finiteJson(contentType?.toString(), status.value, encoding)
            ) {
                call.flushRepresentationHeaders()
                content
            } else {
                call.attributes.remove(pendingHeadersKey)
                try {
                    val fixed =
                        call.response.headers
                            .allValues()
                            .names()
                            .filter(ResponsePolicy::invalidatedHeader)
                    check(fixed.isEmpty()) {
                        "Ktor cannot remove already-appended representation headers ($fixed); use representationHeader or OutgoingContent.headers"
                    }
                    val bytes = FiniteJson.capture(content, request.settings().maxPayloadBytes())
                    val selected =
                        FiniteJson.project(
                            bytes,
                            request,
                            contentType!!.charset() ?: Charsets.UTF_8,
                        )
                    ProjectedContent(selected, contentType, content)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw IllegalStateException("JSON response projection failed", failure)
                }
            }
        }
    }

/**
 * Defers body-dependent metadata until projection eligibility is known. Ktor's ResponseHeaders are
 * append-only; ordinary unrelated headers still use call.response.headers.append as usual.
 */
fun ApplicationCall.representationHeader(name: String, value: String) {
    require(ResponsePolicy.invalidatedHeader(name)) {
        "Expected a body-dependent representation header"
    }
    val projection = attributes.getOrNull(projectionKey)
    check(projection != null) {
        "Install Catnap on this application or route before using representationHeader"
    }
    if (!projection.active()) response.headers.append(name, value, safeOnly = false)
    else attributes.computeIfAbsent(pendingHeadersKey) { HeadersBuilder() }.append(name, value)
}

private fun ApplicationCall.flushRepresentationHeaders() {
    attributes.getOrNull(pendingHeadersKey)?.build()?.forEach { name, values ->
        values.forEach { response.headers.append(name, it, safeOnly = false) }
    }
    attributes.remove(pendingHeadersKey)
}

private class ProjectedContent(
    private val value: ByteArray,
    override val contentType: ContentType,
    private val original: OutgoingContent,
) : OutgoingContent.ByteArrayContent() {
    override val status = original.status
    override val contentLength = value.size.toLong()
    override val headers = original.headers.withoutRepresentationHeaders()

    override fun trailers() = original.trailers()?.withoutRepresentationHeaders()

    override fun <T : Any> getProperty(key: AttributeKey<T>): T? =
        super.getProperty(key)
            ?: if (key == VersionListProperty) null else original.getProperty(key)

    override fun bytes() = value
}

private fun Headers.withoutRepresentationHeaders() =
    Headers.build {
        this@withoutRepresentationHeaders.forEach { name, values ->
            if (!ResponsePolicy.invalidatedHeader(name)) appendAll(name, values)
        }
    }

private object BindQuery : Hook<suspend (PipelineCall) -> Boolean> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (PipelineCall) -> Boolean,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { if (!handler(call)) finish() }
    }
}

private object ProjectRenderedJson :
    Hook<suspend (PipelineCall, OutgoingContent) -> OutgoingContent> {
    private val phase = PipelinePhase("CatnapProjection")

    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (PipelineCall, OutgoingContent) -> OutgoingContent,
    ) {
        pipeline.sendPipeline.insertPhaseAfter(ApplicationSendPipeline.Render, phase)
        pipeline.sendPipeline.intercept(phase) {
            val content = subject as? OutgoingContent ?: return@intercept
            subject = handler(call, content)
        }
    }
}
