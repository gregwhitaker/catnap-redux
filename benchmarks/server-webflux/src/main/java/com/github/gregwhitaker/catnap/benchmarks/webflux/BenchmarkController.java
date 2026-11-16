package com.github.gregwhitaker.catnap.benchmarks.webflux;

import com.github.gregwhitaker.catnap.benchmarks.stream.BenchmarkControl;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxStreams;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class BenchmarkController {
  private final CatnapWebFluxStreams streams;
  private final ObjectWriter writer;

  public BenchmarkController(CatnapWebFluxStreams streams, JsonMapper mapper) {
    this.streams = streams;
    writer = mapper.writerFor(Map.class);
  }

  private Flux<Map<String, Object>> source(int rate, String trace) {
    return StreamTrace.source(
        trace, JdkFlowAdapter.flowPublisherToFlux(BenchmarkControl.source(rate)));
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
  public Flux<Map<String, Object>> nativeNdjson(
      @RequestParam(defaultValue = "0") int rate, @RequestParam(defaultValue = "") String trace) {
    return source(rate, trace);
  }

  @CatnapProjection(enabled = false)
  @GetMapping(value = "/benchmark/native/sse", produces = "text/event-stream")
  public Flux<ServerSentEvent<Map<String, Object>>> nativeSse(
      @RequestParam(defaultValue = "0") int rate, @RequestParam(defaultValue = "") String trace) {
    return source(rate, trace).map(BenchmarkController::event);
  }

  @GetMapping("/benchmark/metrics")
  public Map<String, Object> metrics(@RequestParam(defaultValue = "false") boolean collect) {
    return BenchmarkControl.metrics(collect);
  }

  @GetMapping("/benchmark/pause")
  public Map<String, Object> pause(@RequestParam boolean value) {
    return BenchmarkControl.pause(value);
  }

  @GetMapping("/benchmark/trace")
  public Map<String, Object> trace(@RequestParam(defaultValue = "false") boolean threads) {
    return StreamTrace.snapshot(threads);
  }

  @GetMapping("/benchmark/catnap/ndjson")
  public Mono<Void> projectedNdjson(
      ServerWebExchange exchange,
      @RequestParam(defaultValue = "0") int rate,
      @RequestParam(defaultValue = "") String trace) {
    var encoder =
        streams.<Map<String, Object>>encoder(
            exchange, (value, output) -> writer.writeValue(output, value));
    return streams.writeNdjson(exchange, source(rate, trace), encoder);
  }

  @GetMapping("/benchmark/catnap/sse")
  public Mono<Void> projectedSse(
      ServerWebExchange exchange,
      @RequestParam(defaultValue = "0") int rate,
      @RequestParam(defaultValue = "") String trace) {
    var encoder =
        streams.<Map<String, Object>>encoder(
            exchange, (value, output) -> writer.writeValue(output, value));
    return streams.writeSse(
        exchange, source(rate, trace).map(value -> event(StreamPayload.json(value))), encoder);
  }
}
