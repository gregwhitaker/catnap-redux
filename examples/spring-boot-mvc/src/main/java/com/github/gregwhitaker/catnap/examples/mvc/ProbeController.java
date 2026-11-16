package com.github.gregwhitaker.catnap.examples.mvc;

import com.github.gregwhitaker.catnap.examples.jackson3.JacksonProbe;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@CatnapProjection(enabled = false)
public class ProbeController {
  private final JacksonProbe probe;
  private final JsonMapper mapper;

  ProbeController(JacksonProbe probe, JsonMapper mapper) {
    this.probe = probe;
    this.mapper = mapper;
  }

  @GetMapping(value = "/probe/json", produces = MediaType.APPLICATION_JSON_VALUE)
  JsonNode json(
      @RequestParam String fields, @RequestParam String requestId, @RequestParam String view) {
    return probe.project(ProbeRequest.from(fields, requestId, view));
  }

  @GetMapping(value = "/probe/ndjson", produces = "application/x-ndjson")
  ResponseEntity<StreamingResponseBody> ndjson(
      @RequestParam String fields, @RequestParam String requestId, @RequestParam String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    StreamingResponseBody body =
        output -> {
          output.write(mapper.writeValueAsBytes(probe.project(request)));
          output.write("\n".getBytes(StandardCharsets.UTF_8));
          output.flush();
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/x-ndjson"))
        .body(body);
  }

  @GetMapping(value = "/probe/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter sse(
      @RequestParam String fields, @RequestParam String requestId, @RequestParam String view) {
    ProbeRequest request = ProbeRequest.from(fields, requestId, view);
    SseEmitter emitter = new SseEmitter(10_000L);
    CompletableFuture.runAsync(
        () -> {
          try {
            // Explicit send wrapper carries request state across callbacks, before SSE encoding.
            sendProjected(emitter, request);
            emitter.complete();
          } catch (Exception failure) {
            emitter.completeWithError(failure);
          }
        });
    return emitter;
  }

  private void sendProjected(SseEmitter emitter, ProbeRequest request) throws IOException {
    emitter.send(
        SseEmitter.event()
            .id(request.requestId())
            .name("quote")
            .reconnectTime(1500)
            .comment("probe-heartbeat")
            .data(probe.project(request), MediaType.APPLICATION_JSON));
  }
}
