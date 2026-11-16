package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.examples.webflux.ProjectionController.*;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxStreams;
import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class StreamingController {
  private final CatnapWebFluxStreams streams;
  private final JsonMapper mapper;
  private final ObjectWriter quoteWriter;

  StreamingController(CatnapWebFluxStreams streams, JsonMapper mapper) {
    this.streams = streams;
    this.mapper = mapper;
    quoteWriter =
        mapper
            .writerFor(new TypeReference<Envelope<Quote>>() {})
            .withView(Public.class)
            .withAttribute("currency", "EUR");
  }

  private Envelope<Quote> quote() {
    return new Envelope<>(
        new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
  }

  @GetMapping("/catnap/stream/ndjson")
  Mono<Void> ndjson(ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setETag("\"original\"");
    exchange.getResponse().getHeaders().set("X-Application", "preserved");
    return streams.writeNdjson(
        exchange,
        Flux.just(quote(), quote()),
        streams.encoder(exchange, (value, output) -> quoteWriter.writeValue(output, value)));
  }

  @CatnapProjection(enabled = false)
  @GetMapping("/catnap/stream/disabled")
  Mono<Void> disabled(ServerWebExchange exchange) {
    return ndjson(exchange);
  }

  @GetMapping("/catnap/stream/shapes")
  Mono<Void> shapes(ServerWebExchange exchange) {
    var source =
        Flux.fromIterable(
                List.of("[{\"id\":1,\"other\":2},{\"other\":3},null,7]", "null", "42", "[]", "{}"))
            .map(mapper::readTree);
    return streams.writeNdjson(
        exchange,
        source,
        streams.encoder(exchange, (value, output) -> mapper.writer().writeValue(output, value)));
  }

  @GetMapping("/catnap/stream/empty")
  Mono<Void> empty(ServerWebExchange exchange) {
    return streams.writeNdjson(
        exchange,
        Flux.<JsonNode>empty(),
        streams.encoder(exchange, (value, output) -> mapper.writer().writeValue(output, value)));
  }

  @GetMapping("/catnap/stream/sse")
  Mono<Void> sse(ServerWebExchange exchange) {
    var source =
        Flux.just(
            ServerSentEvent.builder(StreamPayload.json(quote()))
                .event("quote")
                .id("42")
                .retry(Duration.ofMillis(1500))
                .comment("first\nsecond")
                .build(),
            ServerSentEvent.builder(StreamPayload.<Envelope<Quote>>json(null)).id("43").build(),
            ServerSentEvent.builder(
                    StreamPayload.<Envelope<Quote>>text("{\"private\":true}\nplain text"))
                .event("text")
                .id("44")
                .build(),
            ServerSentEvent.<StreamPayload<Envelope<Quote>>>builder()
                .id("")
                .retry(Duration.ZERO)
                .comment("heartbeat")
                .build());
    return streams.writeSse(
        exchange,
        source,
        streams.encoder(exchange, (value, output) -> quoteWriter.writeValue(output, value)));
  }

  @GetMapping("/catnap/stream/encoded")
  Mono<Void> encoded(
      ServerWebExchange exchange, @RequestParam(defaultValue = "valid") String mode) {
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
    if (boundary < bytes.length)
      chunks.add(java.util.Arrays.copyOfRange(bytes, boundary, bytes.length));
    return streams.writeEncodedNdjson(
        exchange,
        Flux.fromIterable(chunks),
        streams.encoder(exchange, (value, output) -> mapper.writer().writeValue(output, value)));
  }

  @GetMapping("/catnap/stream/invalid-event")
  Mono<Void> invalidEvent(
      ServerWebExchange exchange, @RequestParam(defaultValue = "malformed") String mode) {
    StreamPayload<JsonNode> payload =
        switch (mode) {
          case "oversized" -> StreamPayload.text("x".repeat(10_000));
          case "metadata" -> StreamPayload.json(mapper.nullNode());
          default -> StreamPayload.encodedJson("{\"private\":\"never leaked\",");
        };
    var event =
        ServerSentEvent.builder(payload)
            .comment(mode.equals("metadata") ? "x".repeat(10_000) : null)
            .build();
    return streams.writeSse(
        exchange,
        Flux.just(event),
        streams.encoder(exchange, (value, output) -> mapper.writer().writeValue(output, value)));
  }

  @GetMapping("/catnap/stream/lifecycle/{protocol}")
  Mono<Void> lifecycle(
      ServerWebExchange exchange,
      @PathVariable String protocol,
      @RequestParam(defaultValue = "gated") String mode,
      @RequestParam(defaultValue = "") String key,
      @RequestParam(defaultValue = "false") boolean recover) {
    var probe = StreamLifecycleProbe.CONNECTIONS.getOrDefault(key, new StreamLifecycleProbe());
    Flux<JsonNode> source;
    if (mode.equals("infinite"))
      source =
          Flux.generate(
              () -> 0,
              (Integer i, reactor.core.publisher.SynchronousSink<JsonNode> sink) -> {
                sink.next(value(i, "x".repeat(3800)));
                return i + 1;
              });
    else if (mode.equals("before"))
      source = Flux.error(new IOException("Source failed before emission"));
    else
      source =
          Flux.just(value(0, "private"))
              .concatWith(
                  Mono.fromFuture(probe.release)
                      .thenMany(
                          Flux.defer(
                              () ->
                                  mode.equals("after")
                                      ? Flux.error(new IOException("Source failed after emission"))
                                      : Flux.just(
                                          value(
                                              1,
                                              mode.equals("limit")
                                                  ? "x".repeat(10_000)
                                                  : "private")))));
    source =
        source
            .doOnNext(item -> probe.emitted.incrementAndGet())
            .doFinally(signal -> probe.finished.complete(null));
    var encoder =
        streams.<JsonNode>encoder(
            exchange,
            (value, output) -> {
              boolean second = value.get("id").asInt() == 1;
              if (mode.equals("serialize-before") || (mode.equals("serialize") && second))
                throw new IOException("Custom serializer: never leaked");
              if (mode.equals("parse") && second)
                output.write("{\"private\":\"never leaked\",".getBytes(StandardCharsets.UTF_8));
              else if (mode.equals("depth") && second)
                output.write(
                    ("[".repeat(132) + "0" + "]".repeat(132)).getBytes(StandardCharsets.UTF_8));
              else mapper.writer().writeValue(output, value);
            });
    if (protocol.equals("sse"))
      return streams.writeSse(
          exchange,
          source.map(
              item ->
                  ServerSentEvent.builder(StreamPayload.json(item))
                      .id(item.get("id").asString())
                      .event("item")
                      .build()),
          encoder,
          recover
              ? failure ->
                  ServerSentEvent.builder("temporary failure")
                      .event("application-error")
                      .id("failure")
                      .build()
              : null);
    return streams.writeNdjson(exchange, source, encoder);
  }

  private JsonNode value(int id, String other) {
    return mapper.createObjectNode().put("id", id).put("other", other);
  }
}
