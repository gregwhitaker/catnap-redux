package com.github.gregwhitaker.catnap.benchmarks.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gregwhitaker.catnap.benchmarks.stream.BenchmarkControl;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.quarkus.CatnapQuarkusResponses;
import com.github.gregwhitaker.catnap.quarkus.CatnapStream;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.Map;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;

@Path("/benchmark")
public class BenchmarkResource {
  @Inject CatnapQuarkusResponses streams;
  @Inject ObjectMapper mapper;
  @Inject Sse sse;

  private OutboundSseEvent event(Object value, Class<?> type) {
    return sse.newEventBuilder()
        .id("1")
        .name("sample")
        .reconnectDelay(1000)
        .comment("benchmark")
        .mediaType(MediaType.APPLICATION_JSON_TYPE)
        .data(type, value)
        .build();
  }

  @GET
  @Path("/native/ndjson")
  @Produces("application/x-ndjson")
  @CatnapProjection(enabled = false)
  public Multi<Map<String, Object>> nativeNdjson(@QueryParam("rate") @DefaultValue("0") int rate) {
    return Multi.createFrom().publisher(BenchmarkControl.source(rate));
  }

  @GET
  @Path("/native/sse")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @CatnapProjection(enabled = false)
  public Multi<OutboundSseEvent> nativeSse(@QueryParam("rate") @DefaultValue("0") int rate) {
    return nativeNdjson(rate).map(value -> event(value, Map.class));
  }

  @GET
  @Path("/catnap/ndjson")
  @Produces("application/x-ndjson")
  public CatnapStream projectedNdjson(
      @Context ServerRequestContext request, @QueryParam("rate") @DefaultValue("0") int rate) {
    var writer = mapper.writerFor(Map.class);
    return streams.ndjson(
        request,
        BenchmarkControl.source(rate),
        streams.<Map<String, Object>>encoder(
            request, (value, output) -> writer.writeValue(output, value)));
  }

  @GET
  @Path("/catnap/sse")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  public CatnapStream projectedSse(
      @Context ServerRequestContext request, @QueryParam("rate") @DefaultValue("0") int rate) {
    var writer = mapper.writerFor(Map.class);
    return streams.sse(
        request,
        nativeNdjson(rate).map(value -> event(StreamPayload.json(value), StreamPayload.class)),
        streams.<Map<String, Object>>encoder(
            request, (value, output) -> writer.writeValue(output, value)));
  }

  @GET
  @Path("/metrics")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> metrics(
      @QueryParam("collect") @DefaultValue("false") boolean collect) {
    return BenchmarkControl.metrics(collect);
  }

  @GET
  @Path("/pause")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> pause(@QueryParam("value") boolean value) {
    return BenchmarkControl.pause(value);
  }
}
