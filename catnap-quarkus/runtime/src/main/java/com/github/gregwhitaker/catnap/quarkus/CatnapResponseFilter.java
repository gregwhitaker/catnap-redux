package com.github.gregwhitaker.catnap.quarkus;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

/** Captures the selected native writer before any bytes are committed to the transport. */
@Provider
@Priority(1)
public class CatnapResponseFilter implements ContainerResponseFilter {
  @Inject jakarta.inject.Provider<ProjectionService> services;
  private volatile Jackson2Json json;
  private volatile JsonMapper mapper;

  private synchronized void initialize() {
    if (json != null) return;
    var service = services.get();
    mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(service.settings().evaluation().maxDepth() + 1)
                            .build())
                    .build())
            .build();
    json = new Jackson2Json(mapper);
  }

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    var projection = (ProjectionRequest) request.getProperty(CatnapRequestFilter.PROJECTION);
    if (response.hasEntity()
        && request.getProperty(CatnapQuarkusResponses.PROJECTED) == response.getEntity()) {
      if (projection != null && projection.active()) {
        response.getHeaders().keySet().removeIf(ResponsePolicy::invalidatedHeader);
        response.getHeaders().putSingle("Content-Length", ((byte[]) response.getEntity()).length);
      }
      return;
    }
    if (projection == null
        || !projection.active()
        || !response.hasEntity()
        || "HEAD".equals(request.getMethod())) return;
    var context =
        (ResteasyReactiveRequestContext)
            ((ResteasyReactiveContainerRequestContext) request).getServerRequestContext();
    var mediaType = response.getMediaType();
    if (mediaType == null) mediaType = context.getResponseMediaType();
    if (mediaType == null
        && context.getTarget() != null
        && context.getTarget().getProduces() != null) {
      mediaType =
          context
              .getTarget()
              .getProduces()
              .negotiateProduces(request.getHeaderString("Accept"))
              .getKey();
    }
    if (!ResponsePolicy.finiteJson(
        mediaType == null ? null : mediaType.toString(),
        response.getStatus(),
        response.getHeaderString("Content-Encoding"))) return;
    response.getHeaders().putSingle("Content-Type", mediaType);
    context.setResponseContentType(mediaType);
    try {
      if (json == null) initialize();
      byte[] captured =
          NativeSerialization.capture(context, response, projection.settings().maxPayloadBytes());
      var selected = projection.evaluate(json.parse(captured), json.adapter());
      Charset charset =
          mediaType.getParameters().containsKey("charset")
              ? Charset.forName(mediaType.getParameters().get("charset"))
              : StandardCharsets.UTF_8;
      BoundedBuffer encoded = new BoundedBuffer(projection.settings().maxPayloadBytes());
      mapper.writeValue(new OutputStreamWriter(encoded, charset), selected);
      byte[] bytes = encoded.toByteArray();
      response.getHeaders().keySet().removeIf(ResponsePolicy::invalidatedHeader);
      response.getHeaders().putSingle("Content-Length", bytes.length);
      response.setEntity(new GenericEntity<byte[]>(bytes) {});
      // Application writer interceptors ran against the original entity during capture.
      context.setWriterInterceptors(null);
    } catch (IOException | RuntimeException failure) {
      response.getHeaders().keySet().removeIf(ResponsePolicy::invalidatedHeader);
      throw new InternalServerErrorException("JSON response projection failed", failure);
    }
  }
}
