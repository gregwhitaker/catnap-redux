package com.github.gregwhitaker.catnap.benchmarks.micronaut;

import com.github.gregwhitaker.catnap.benchmarks.stream.BenchmarkControl;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.micronaut.CatnapMicronautStreams;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.http.sse.Event;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.reactivestreams.Publisher;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

@Controller("/benchmark")
public class BenchmarkController {
  private final CatnapMicronautStreams streams;
  private final JsonMapper mapper;

  public BenchmarkController(CatnapMicronautStreams streams, JsonMapper mapper) {
    this.streams = streams;
    this.mapper = mapper;
  }

  private Flux<Map<String, Object>> source(int rate) {
    return JdkFlowAdapter.flowPublisherToFlux(BenchmarkControl.source(rate));
  }

  private static <T> Event<T> event(T value) {
    return Event.of(value)
        .id("1")
        .name("sample")
        .retry(Duration.ofMillis(1000))
        .comment("benchmark");
  }

  @CatnapProjection(enabled = false)
  @Get(value = "/native/ndjson", produces = "application/x-ndjson")
  public Publisher<byte[]> nativeNdjson(@QueryValue(defaultValue = "0") int rate) {
    // This Micronaut version has no typed writer for application/x-ndjson. Use its configured
    // native mapper and append only the NDJSON delimiter, as an application would without Catnap.
    return source(rate)
        .map(
            value -> {
              try {
                byte[] json =
                    mapper.writeValueAsBytes(Argument.mapOf(String.class, Object.class), value);
                byte[] record = Arrays.copyOf(json, json.length + 1);
                record[record.length - 1] = '\n';
                return record;
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            });
  }

  @CatnapProjection(enabled = false)
  @Get(value = "/native/sse", produces = "text/event-stream")
  public Publisher<Event<Map<String, Object>>> nativeSse(@QueryValue(defaultValue = "0") int rate) {
    return source(rate).map(BenchmarkController::event);
  }

  @Get("/catnap/ndjson")
  public MutableHttpResponse<?> projectedNdjson(
      HttpRequest<?> request, @QueryValue(defaultValue = "0") int rate) {
    return streams.ndjson(
        request,
        source(rate),
        streams.encoder(request, mapper, Argument.mapOf(String.class, Object.class)));
  }

  @Get("/catnap/sse")
  public MutableHttpResponse<?> projectedSse(
      HttpRequest<?> request, @QueryValue(defaultValue = "0") int rate) {
    return streams.sse(
        request,
        source(rate).map(value -> event(StreamPayload.json(value))),
        streams.encoder(request, mapper, Argument.mapOf(String.class, Object.class)));
  }

  @Get("/metrics")
  public Map<String, Object> metrics(@QueryValue(defaultValue = "false") boolean collect) {
    return BenchmarkControl.metrics(collect);
  }

  @Get("/pause")
  public Map<String, Object> pause(@QueryValue boolean value) {
    return BenchmarkControl.pause(value);
  }
}
