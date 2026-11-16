package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gregwhitaker.catnap.examples.quarkus.ProjectionResource.*;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.quarkus.CatnapQuarkusResponses;
import com.github.gregwhitaker.catnap.quarkus.CatnapStream;
import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;

@Path("/catnap/stream")
@Produces("application/x-ndjson")
public class StreamingResource {
  @Inject CatnapQuarkusResponses streams;
  @Inject ObjectMapper mapper;
  @Inject Sse sse;

  private Envelope<Quote> quote() {
    return new Envelope<>(
        new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
  }

  private com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder<Envelope<Quote>>
      quoteEncoder(ServerRequestContext request) {
    var writer =
        mapper
            .writerFor(new TypeReference<Envelope<Quote>>() {})
            .withView(Public.class)
            .withAttribute("currency", "EUR");
    return streams.encoder(request, (value, output) -> writer.writeValue(output, value));
  }

  @GET
  @Path("/ndjson")
  public CatnapStream ndjson(@Context ServerRequestContext request) {
    return streams
        .ndjson(request, Multi.createFrom().items(quote(), quote()), quoteEncoder(request))
        .header("ETag", "\"original\"")
        .header("X-Application", "preserved");
  }

  @GET
  @Path("/disabled")
  @CatnapProjection(enabled = false)
  public CatnapStream disabled(@Context ServerRequestContext request) {
    return ndjson(request);
  }

  @GET
  @Path("/shapes")
  public CatnapStream shapes(@Context ServerRequestContext request) {
    var source =
        Multi.createFrom()
            .iterable(
                List.of("[{\"id\":1,\"other\":2},{\"other\":3},null,7]", "null", "42", "[]", "{}"))
            .map(
                text -> {
                  try {
                    return mapper.readTree(text);
                  } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                  }
                });
    return streams.ndjson(
        request,
        source,
        streams.encoder(request, (value, output) -> mapper.writeValue(output, value)));
  }

  @GET
  @Path("/empty")
  public CatnapStream empty(@Context ServerRequestContext request) {
    return streams.ndjson(
        request,
        Multi.createFrom().empty(),
        streams.encoder(request, (value, output) -> mapper.writeValue(output, value)));
  }

  @GET
  @Path("/sse")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  public CatnapStream events(@Context ServerRequestContext request) {
    var source =
        Multi.createFrom()
            .items(
                sse.newEventBuilder()
                    .data(StreamPayload.json(quote()))
                    .name("quote")
                    .id("42")
                    .reconnectDelay(1500)
                    .comment("first\nsecond")
                    .build(),
                sse.newEventBuilder()
                    .data(StreamPayload.<Envelope<Quote>>json(null))
                    .id("43")
                    .build(),
                sse.newEventBuilder()
                    .data(StreamPayload.<Envelope<Quote>>text("{\"private\":true}\nplain text"))
                    .name("text")
                    .id("44")
                    .build(),
                sse.newEventBuilder().id("").reconnectDelay(0).comment("heartbeat").build());
    return streams.sse(request, source, quoteEncoder(request));
  }

  @GET
  @Path("/encoded")
  public CatnapStream encoded(
      @Context ServerRequestContext request,
      @QueryParam("mode") @DefaultValue("valid") String mode) {
    String raw =
        switch (mode) {
          case "blank" -> "\n";
          case "bare-cr" -> "{}\r{}\n";
          case "tail" -> "{}";
          case "malformed" -> "{\"private\":\"never leaked\",\n";
          case "oversized" -> "{\"private\":\"" + "x".repeat(10_000) + "\"}\n";
          case "depth" -> "[".repeat(132) + "0" + "]".repeat(132) + "\n";
          default -> "{\"label\":\"é😀\",\"other\":1}\r\n{\"label\":\"second\",\"other\":2}\n";
        };
    byte[] bytes =
        mode.equals("utf8")
            ? new byte[] {(byte) 0xc3, 0x28, 10}
            : raw.getBytes(StandardCharsets.UTF_8);
    var chunks = new ArrayList<byte[]>();
    int boundary = Math.min(bytes.length, 20);
    for (int i = 0; i < boundary; i++) chunks.add(new byte[] {bytes[i]});
    if (boundary < bytes.length) chunks.add(Arrays.copyOfRange(bytes, boundary, bytes.length));
    return streams.encodedNdjson(
        request,
        Multi.createFrom().iterable(chunks),
        streams.encoder(request, (value, output) -> mapper.writeValue(output, value)));
  }

  @GET
  @Path("/invalid-event")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  public CatnapStream invalid(
      @Context ServerRequestContext request,
      @QueryParam("mode") @DefaultValue("malformed") String mode) {
    StreamPayload<JsonNode> payload =
        switch (mode) {
          case "oversized" -> StreamPayload.text("x".repeat(10_000));
          case "metadata" -> StreamPayload.json(mapper.nullNode());
          default -> StreamPayload.encodedJson("{\"private\":\"never leaked\",");
        };
    var event =
        sse.newEventBuilder()
            .data(payload)
            .comment(mode.equals("metadata") ? "x".repeat(10_000) : null)
            .build();
    return streams.sse(
        request,
        Multi.createFrom().item(event),
        streams.encoder(request, (value, output) -> mapper.writeValue(output, value)));
  }

  @GET
  @Path("/lifecycle/{protocol}")
  @Produces("*/*")
  public CatnapStream lifecycle(
      @Context ServerRequestContext request,
      @PathParam("protocol") String protocol,
      @QueryParam("mode") @DefaultValue("gated") String mode,
      @QueryParam("key") @DefaultValue("") String key,
      @QueryParam("recover") @DefaultValue("false") boolean recover) {
    var probe = StreamLifecycleProbe.CONNECTIONS.getOrDefault(key, new StreamLifecycleProbe());
    var source = Multi.createFrom().publisher(new LifecycleSource(mapper, mode, probe));
    var encoder =
        streams.<JsonNode>encoder(
            request,
            (value, output) -> {
              boolean second = value.get("id").asInt() == 1;
              if (mode.equals("serialize-before") || (mode.equals("serialize") && second))
                throw new IOException("Custom serializer: never leaked");
              if (mode.equals("parse") && second)
                output.write("{\"private\":\"never leaked\",".getBytes(StandardCharsets.UTF_8));
              else if (mode.equals("depth") && second)
                output.write(
                    ("[".repeat(132) + "0" + "]".repeat(132)).getBytes(StandardCharsets.UTF_8));
              else mapper.writeValue(output, value);
            });
    if (protocol.equals("sse"))
      return streams.sse(
          request,
          source.map(
              item ->
                  sse.newEventBuilder()
                      .data(StreamPayload.json(item))
                      .id(item.get("id").asText())
                      .name("item")
                      .build()),
          encoder,
          recover
              ? error ->
                  sse.newEventBuilder()
                      .data("temporary failure")
                      .name("application-error")
                      .id("failure")
                      .build()
              : null);
    return streams.ndjson(request, source, encoder);
  }

  @GET
  @Path("/status")
  @org.jboss.resteasy.reactive.ResponseStatus(201)
  @org.jboss.resteasy.reactive.ResponseHeader(name = "X-Native", value = "preserved")
  @org.jboss.resteasy.reactive.ResponseHeader(name = "ETag", value = "stale")
  public CatnapStream status(@Context ServerRequestContext request) {
    return shapes(request);
  }

  @GET
  @Path("/sse-lines")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  public CatnapStream lineEndings(@Context ServerRequestContext request) {
    var event =
        sse.newEventBuilder()
            .data(StreamPayload.<String>text("one\r\ntwo\rlast\n"))
            .id("id\nignored\rinjected")
            .name("name\r\nignored")
            .comment("first\r\nsecond\rthird\n")
            .build();
    return streams.sse(
        request,
        Multi.createFrom().item(event),
        streams.<String>encoder(
            request,
            (value, output) -> {
              throw new AssertionError("Intentional text");
            }));
  }
}
