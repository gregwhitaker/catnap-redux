package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** A single-use streaming response. Return it directly from a Quarkus REST resource method. */
public final class CatnapStream {
  sealed interface Frame permits JsonRecord, SseFrame {}

  record JsonRecord(byte[] json) implements Frame {}

  record SseFrame(OutboundSseEvent event) implements Frame {}

  final ProjectionRequest query;
  final MediaType mediaType;
  final Flow.Publisher<? extends Frame> source;
  final Function<Throwable, Frame> onError;
  final Map<String, List<String>> headers = new LinkedHashMap<>();
  final AtomicBoolean started = new AtomicBoolean();
  Integer status;

  CatnapStream(
      ProjectionRequest query,
      MediaType mediaType,
      Flow.Publisher<? extends Frame> source,
      Function<Throwable, Frame> onError) {
    this.query = query;
    this.mediaType = mediaType;
    this.source = source;
    this.onError = onError;
  }

  /** Add application metadata before returning this response from the endpoint. */
  public synchronized CatnapStream header(String name, String value) {
    if (started.get()) throw new IllegalStateException("Stream already started");
    headers.put(name, List.of(value));
    return this;
  }

  public synchronized CatnapStream status(int code) {
    if (started.get()) throw new IllegalStateException("Stream already started");
    if (code < 200 || code > 299 || code == 204 || code == 205 || code == 206)
      throw new IllegalArgumentException(
          "A successful status with a complete streaming body is required");
    status = code;
    return this;
  }
}
