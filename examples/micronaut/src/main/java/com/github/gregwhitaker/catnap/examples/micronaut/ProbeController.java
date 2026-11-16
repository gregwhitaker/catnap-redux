package com.github.gregwhitaker.catnap.examples.micronaut;

import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Controller("/probe")
@CatnapProjection(enabled = false)
public class ProbeController {
  private final SerializationProbe probe;
  private final JsonMapper mapper;

  public ProbeController(SerializationProbe probe, JsonMapper mapper) {
    this.probe = probe;
    this.mapper = mapper;
  }

  @Get(value = "/json", produces = "application/json")
  JsonNode json(@QueryValue String fields, @QueryValue String requestId, @QueryValue String view)
      throws IOException {
    return probe.project(ProbeRequest.from(fields, requestId, view));
  }

  @Get(value = "/ndjson", produces = "application/x-ndjson")
  Publisher<byte[]> ndjson(
      @QueryValue String fields, @QueryValue String requestId, @QueryValue String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    return Flux.defer(
            () -> {
              try {
                byte[] json = mapper.writeValueAsBytes(probe.project(request));
                byte[] record = Arrays.copyOf(json, json.length + 1);
                record[record.length - 1] = '\n';
                return Flux.just(record);
              } catch (IOException error) {
                return Flux.error(error);
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Get(value = "/sse", produces = "text/event-stream")
  Publisher<Event<JsonNode>> sse(
      @QueryValue String fields, @QueryValue String requestId, @QueryValue String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    return Flux.defer(
            () -> {
              try {
                return Flux.just(
                    Event.of(probe.project(request))
                        .id(request.requestId())
                        .name("quote")
                        .retry(Duration.ofMillis(1500))
                        .comment("probe-heartbeat"));
              } catch (IOException error) {
                return Flux.error(error);
              }
            })
        .subscribeOn(Schedulers.boundedElastic());
  }
}
