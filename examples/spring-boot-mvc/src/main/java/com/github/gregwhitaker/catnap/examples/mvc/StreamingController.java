package com.github.gregwhitaker.catnap.examples.mvc;

import com.github.gregwhitaker.catnap.examples.mvc.ProjectionController.*;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcStreams;
import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class StreamingController {
  private final CatnapMvcStreams streams;
  private final JsonMapper mapper;
  private final ObjectWriter writer;

  StreamingController(CatnapMvcStreams streams, JsonMapper mapper) {
    this.streams = streams;
    this.mapper = mapper;
    writer =
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
  ResponseBodyEmitter ndjson(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("ETag", "\"original\"");
    response.setHeader("X-Application", "preserved");
    var emitter =
        streams.ndjson(
            request,
            response,
            streams.<Envelope<Quote>>encoder(
                request, (value, output) -> writer.writeValue(output, value)));
    emitter.send(quote());
    emitter.send(quote());
    emitter.complete();
    return emitter.response();
  }

  @GetMapping("/catnap/stream/disabled")
  @CatnapProjection(enabled = false)
  ResponseBodyEmitter disabled(HttpServletRequest request, HttpServletResponse response) {
    return ndjson(request, response);
  }

  @GetMapping("/catnap/stream/async")
  java.util.concurrent.Callable<ResponseBodyEmitter> asynchronous(
      HttpServletRequest request, HttpServletResponse response) {
    return () -> ndjson(request, response);
  }

  @GetMapping("/catnap/stream/shapes")
  ResponseBodyEmitter shapes(HttpServletRequest request, HttpServletResponse response) {
    var emitter =
        streams.ndjson(
            request,
            response,
            streams.<JsonNode>encoder(
                request, (value, output) -> mapper.writeValue(output, value)));
    for (String value :
        List.of("[{\"id\":1,\"other\":2},{\"other\":3},null,7]", "null", "42", "[]", "{}"))
      emitter.send(mapper.readTree(value));
    emitter.complete();
    return emitter.response();
  }

  @GetMapping("/catnap/stream/empty")
  ResponseBodyEmitter empty(HttpServletRequest request, HttpServletResponse response) {
    var emitter =
        streams.ndjson(
            request,
            response,
            streams.encoder(request, (value, output) -> mapper.writeValue(output, value)));
    emitter.complete();
    return emitter.response();
  }

  @GetMapping("/catnap/stream/sse")
  ResponseBodyEmitter sse(HttpServletRequest request, HttpServletResponse response) {
    var emitter =
        streams.sse(
            request,
            response,
            streams.<Envelope<Quote>>encoder(
                request, (value, output) -> writer.writeValue(output, value)));
    emitter.send(
        ServerSentEvent.builder(StreamPayload.json(quote()))
            .event("quote")
            .id("42")
            .retry(Duration.ofMillis(1500))
            .comment("first\nsecond")
            .build());
    emitter.send(
        ServerSentEvent.builder(StreamPayload.<Envelope<Quote>>json(null)).id("43").build());
    emitter.send(
        ServerSentEvent.builder(
                StreamPayload.<Envelope<Quote>>text("{\"private\":true}\nplain text"))
            .event("text")
            .id("44")
            .build());
    emitter.send(
        ServerSentEvent.<StreamPayload<Envelope<Quote>>>builder()
            .id("")
            .retry(Duration.ZERO)
            .comment("heartbeat")
            .build());
    emitter.complete();
    return emitter.response();
  }

  @GetMapping("/catnap/stream/encoded")
  ResponseBodyEmitter encoded(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(defaultValue = "valid") String mode) {
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
    var input =
        new ByteArrayInputStream(bytes) {
          @Override
          public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, pos < 20 ? 1 : length));
          }
        };
    return streams
        .encodedNdjson(
            request,
            response,
            input,
            streams.encoder(request, (value, output) -> mapper.writeValue(output, value)))
        .response();
  }

  @GetMapping("/catnap/stream/invalid-event")
  ResponseBodyEmitter invalidEvent(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(defaultValue = "malformed") String mode) {
    StreamPayload<JsonNode> payload =
        switch (mode) {
          case "oversized" -> StreamPayload.text("x".repeat(10_000));
          case "metadata" -> StreamPayload.json(mapper.nullNode());
          default -> StreamPayload.encodedJson("{\"private\":\"never leaked\",");
        };
    var emitter =
        streams.sse(
            request,
            response,
            streams.<JsonNode>encoder(
                request, (value, output) -> mapper.writeValue(output, value)));
    emitter.send(
        ServerSentEvent.builder(payload)
            .comment(mode.equals("metadata") ? "x".repeat(10_000) : null)
            .build());
    emitter.complete();
    return emitter.response();
  }

  @GetMapping("/catnap/stream/lifecycle/{protocol}")
  ResponseBodyEmitter lifecycle(
      HttpServletRequest request,
      HttpServletResponse response,
      @PathVariable String protocol,
      @RequestParam(defaultValue = "gated") String mode,
      @RequestParam(defaultValue = "") String key,
      @RequestParam(defaultValue = "false") boolean recover) {
    var probe = StreamLifecycleProbe.CONNECTIONS.getOrDefault(key, new StreamLifecycleProbe());
    var source = new LifecycleSource(mapper, mode, probe);
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
    if (protocol.equals("sse")) {
      var emitter =
          streams.sse(
              request,
              response,
              encoder,
              recover
                  ? failure ->
                      ServerSentEvent.builder("temporary failure")
                          .event("application-error")
                          .id("failure")
                          .build()
                  : null);
      emitter.subscribe(
          (Flow.Publisher<ServerSentEvent<StreamPayload<JsonNode>>>)
              downstream ->
                  source.subscribe(
                      new Flow.Subscriber<JsonNode>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                          downstream.onSubscribe(subscription);
                        }

                        @Override
                        public void onNext(JsonNode value) {
                          downstream.onNext(
                              ServerSentEvent.builder(StreamPayload.json(value))
                                  .id(value.get("id").asString())
                                  .event("item")
                                  .build());
                        }

                        @Override
                        public void onError(Throwable failure) {
                          downstream.onError(failure);
                        }

                        @Override
                        public void onComplete() {
                          downstream.onComplete();
                        }
                      }));
      return emitter.response();
    }
    var emitter = streams.ndjson(request, response, encoder);
    emitter.subscribe(source);
    return emitter.response();
  }
}
