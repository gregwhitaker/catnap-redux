package com.github.gregwhitaker.catnap.examples.micronaut;

import com.github.gregwhitaker.catnap.examples.micronaut.ProjectionController.*;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.micronaut.CatnapMicronautStreams;
import com.github.gregwhitaker.catnap.micronaut.MicronautJson;
import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.http.sse.Event;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller("/catnap/stream")
public class StreamingController {
  private final CatnapMicronautStreams streams;
  private final JsonMapper mapper;
  private final JsonMapper quoteMapper;
  private final Argument<Envelope<Quote>> quoteType =
      (Argument) Argument.of(Envelope.class, Quote.class);
  private final MicronautJson json = new MicronautJson(128);

  StreamingController(CatnapMicronautStreams streams, JsonMapper mapper) {
    this.streams = streams;
    this.mapper = mapper;
    quoteMapper = mapper.cloneWithViewClass(Public.class);
  }

  private Envelope<Quote> quote() {
    return new Envelope<>(
        new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
  }

  @Get("/ndjson")
  MutableHttpResponse<?> ndjson(HttpRequest<?> request) {
    return streams
        .ndjson(
            request, Flux.just(quote(), quote()), streams.encoder(request, quoteMapper, quoteType))
        .header("ETag", "\"original\"")
        .header("X-Application", "preserved");
  }

  @CatnapProjection(enabled = false)
  @Get("/disabled")
  MutableHttpResponse<?> disabled(HttpRequest<?> request) {
    return ndjson(request);
  }

  @Get("/shapes")
  MutableHttpResponse<?> shapes(HttpRequest<?> request) {
    var source =
        Flux.fromIterable(
                List.of("[{\"id\":1,\"other\":2},{\"other\":3},null,7]", "null", "42", "[]", "{}"))
            .map(this::parse);
    return streams.ndjson(
        request, source, streams.encoder(request, mapper, Argument.of(JsonNode.class)));
  }

  @Get("/empty")
  MutableHttpResponse<?> empty(HttpRequest<?> request) {
    return streams.ndjson(
        request,
        Flux.<JsonNode>empty(),
        streams.encoder(request, mapper, Argument.of(JsonNode.class)));
  }

  @Get("/sse")
  MutableHttpResponse<?> sse(HttpRequest<?> request) {
    var source =
        Flux.just(
            Event.of(StreamPayload.json(quote()))
                .name("quote")
                .id("42")
                .retry(Duration.ofMillis(1500))
                .comment("first\nsecond"),
            Event.of(StreamPayload.<Envelope<Quote>>json(null)).id("43"),
            Event.of(StreamPayload.<Envelope<Quote>>text("{\"private\":true}\nplain text"))
                .name("text")
                .id("44"),
            Event.of(StreamPayload.<Envelope<Quote>>text(""))
                .id("")
                .retry(Duration.ZERO)
                .comment("heartbeat"));
    return streams.sse(request, source, streams.encoder(request, quoteMapper, quoteType));
  }

  @Get("/encoded")
  MutableHttpResponse<?> encoded(
      HttpRequest<?> request, @QueryValue(defaultValue = "valid") String mode) {
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
    return streams.encodedNdjson(
        request,
        Flux.fromIterable(chunks),
        streams.encoder(request, mapper, Argument.of(JsonNode.class)));
  }

  @Get("/invalid-event")
  MutableHttpResponse<?> invalidEvent(
      HttpRequest<?> request, @QueryValue(defaultValue = "malformed") String mode) {
    StreamPayload<JsonNode> payload =
        switch (mode) {
          case "oversized" -> StreamPayload.text("x".repeat(10_000));
          case "metadata" -> StreamPayload.json(JsonNode.nullNode());
          default -> StreamPayload.encodedJson("{\"private\":\"never leaked\",");
        };
    var event = Event.of(payload).comment(mode.equals("metadata") ? "x".repeat(10_000) : null);
    return streams.sse(
        request, Flux.just(event), streams.encoder(request, mapper, Argument.of(JsonNode.class)));
  }

  @Get("/lifecycle/{protocol}")
  MutableHttpResponse<?> lifecycle(
      HttpRequest<?> request,
      @PathVariable String protocol,
      @QueryValue(defaultValue = "gated") String mode,
      @QueryValue(defaultValue = "") String key,
      @QueryValue(defaultValue = "false") boolean recover) {
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
            request,
            (value, output) -> {
              boolean second = value.get("id").getIntValue() == 1;
              if (mode.equals("serialize-before") || (mode.equals("serialize") && second))
                throw new IOException("Custom serializer: never leaked");
              if (mode.equals("parse") && second)
                output.write("{\"private\":\"never leaked\",".getBytes(StandardCharsets.UTF_8));
              else if (mode.equals("depth") && second)
                output.write(
                    ("[".repeat(132) + "0" + "]".repeat(132)).getBytes(StandardCharsets.UTF_8));
              else mapper.writeValue(output, Argument.of(JsonNode.class), value);
            });
    if (protocol.equals("sse"))
      return streams.sse(
          request,
          source.map(
              item ->
                  Event.of(StreamPayload.json(item))
                      .id(Integer.toString(item.get("id").getIntValue()))
                      .name("item")),
          encoder,
          recover
              ? failure -> Event.of("temporary failure").name("application-error").id("failure")
              : null);
    return streams.ndjson(request, source, encoder);
  }

  private JsonNode parse(String value) {
    try {
      return json.parse(value.getBytes(StandardCharsets.UTF_8));
    } catch (IOException failure) {
      throw new IllegalArgumentException(failure);
    }
  }

  private JsonNode value(int id, String other) {
    return JsonNode.createObjectNode(
        Map.of("id", JsonNode.createNumberNode(id), "other", JsonNode.createStringNode(other)));
  }
}
