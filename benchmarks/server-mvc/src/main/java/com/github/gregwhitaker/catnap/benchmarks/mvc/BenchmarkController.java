package com.github.gregwhitaker.catnap.benchmarks.mvc;

import com.github.gregwhitaker.catnap.benchmarks.stream.BenchmarkControl;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcStreams;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class BenchmarkController {
  private final CatnapMvcStreams streams;
  private final ObjectWriter writer;

  public BenchmarkController(CatnapMvcStreams streams, JsonMapper mapper) {
    this.streams = streams;
    writer = mapper.writerFor(Map.class);
  }

  private Flux<Map<String, Object>> source(int rate) {
    return JdkFlowAdapter.flowPublisherToFlux(BenchmarkControl.source(rate));
  }

  private static <T> ServerSentEvent<T> event(T value) {
    return ServerSentEvent.builder(value)
        .id("1")
        .event("sample")
        .retry(Duration.ofMillis(1000))
        .comment("benchmark")
        .build();
  }

  @CatnapProjection(enabled = false)
  @GetMapping(value = "/benchmark/native/ndjson", produces = "application/x-ndjson")
  public Flux<Map<String, Object>> nativeNdjson(@RequestParam(defaultValue = "0") int rate) {
    return source(rate);
  }

  @CatnapProjection(enabled = false)
  @GetMapping(value = "/benchmark/native/sse", produces = "text/event-stream")
  public Flux<ServerSentEvent<Map<String, Object>>> nativeSse(
      @RequestParam(defaultValue = "0") int rate) {
    return source(rate).map(BenchmarkController::event);
  }

  @GetMapping("/benchmark/metrics")
  public Map<String, Object> metrics(@RequestParam(defaultValue = "false") boolean collect) {
    return BenchmarkControl.metrics(collect);
  }

  @GetMapping("/benchmark/pause")
  public Map<String, Object> pause(@RequestParam boolean value) {
    return BenchmarkControl.pause(value);
  }

  @GetMapping("/benchmark/catnap/ndjson")
  public ResponseBodyEmitter projectedNdjson(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(defaultValue = "0") int rate) {
    var encoder =
        streams.<Map<String, Object>>encoder(
            request, (value, output) -> writer.writeValue(output, value));
    var emitter = streams.ndjson(request, response, encoder);
    emitter.subscribe(BenchmarkControl.source(rate));
    return emitter.response();
  }

  @GetMapping("/benchmark/catnap/sse")
  public ResponseBodyEmitter projectedSse(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(defaultValue = "0") int rate) {
    var encoder =
        streams.<Map<String, Object>>encoder(
            request, (value, output) -> writer.writeValue(output, value));
    var emitter = streams.sse(request, response, encoder);
    emitter.subscribe(source(rate).map(value -> event(StreamPayload.json(value))));
    return emitter.response();
  }
}
