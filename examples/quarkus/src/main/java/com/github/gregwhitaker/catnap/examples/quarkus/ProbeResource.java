package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/probe")
@CatnapProjection(enabled = false)
public class ProbeResource {
  @Inject SerializationProbe probe;
  @Inject Sse sse;

  @GET
  @Path("/json")
  @Produces(MediaType.APPLICATION_JSON)
  public JsonNode json(
      @QueryParam("fields") String fields,
      @QueryParam("requestId") String requestId,
      @QueryParam("view") String view) {
    return probe.project(ProbeRequest.from(fields, requestId, view));
  }

  @GET
  @Path("/ndjson")
  @Produces("application/x-ndjson")
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<JsonNode> ndjson(
      @QueryParam("fields") String fields,
      @QueryParam("requestId") String requestId,
      @QueryParam("view") String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    return Multi.createFrom().item(() -> probe.project(request));
  }

  @GET
  @Path("/sse")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<OutboundSseEvent> events(
      @QueryParam("fields") String fields,
      @QueryParam("requestId") String requestId,
      @QueryParam("view") String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    return Multi.createFrom()
        .item(
            () ->
                sse.newEventBuilder()
                    .id(request.requestId())
                    .name("quote")
                    .reconnectDelay(1500)
                    .comment("probe-heartbeat")
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(JsonNode.class, probe.project(request))
                    .build());
  }
}
