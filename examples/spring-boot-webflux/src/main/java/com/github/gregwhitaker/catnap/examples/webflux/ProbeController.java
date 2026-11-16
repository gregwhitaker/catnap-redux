package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.examples.jackson3.JacksonProbe;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

@RestController
@CatnapProjection(enabled = false)
public class ProbeController {
  private final JacksonProbe probe;

  ProbeController(JacksonProbe probe) {
    this.probe = probe;
  }

  @GetMapping(value = "/probe/json", produces = MediaType.APPLICATION_JSON_VALUE)
  JsonNode json(
      @RequestParam String fields, @RequestParam String requestId, @RequestParam String view) {
    return probe.project(ProbeRequest.from(fields, requestId, view));
  }

  @GetMapping(value = "/probe/ndjson", produces = "application/x-ndjson")
  Flux<JsonNode> ndjson(
      @RequestParam String fields, @RequestParam String requestId, @RequestParam String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    return Flux.defer(() -> Flux.just(probe.project(request)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping(value = "/probe/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  Flux<ServerSentEvent<JsonNode>> sse(
      @RequestParam String fields, @RequestParam String requestId, @RequestParam String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    return Flux.defer(
            () ->
                Flux.just(
                    ServerSentEvent.builder(probe.project(request))
                        .id(request.requestId())
                        .event("quote")
                        .retry(Duration.ofMillis(1500))
                        .comment("probe-heartbeat")
                        .build()))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
