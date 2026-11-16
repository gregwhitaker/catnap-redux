package com.github.gregwhitaker.catnap.spring.mvc;

import com.github.gregwhitaker.catnap.http.*;
import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.streams.NdjsonReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/** Bounded asynchronous MVC send/emitter integration, with no Reactor or WebFlux dependency. */
public final class CatnapMvcStreams implements AutoCloseable {
  private final ProjectionService service;
  private final MvcStreamOptions options;
  private final ThreadPoolExecutor workers;
  private final Set<CatnapMvcEmitter<?>> connections = ConcurrentHashMap.newKeySet();
  private final JsonMapper mapper;
  private final Jackson3Json json;

  public CatnapMvcStreams(ProjectionService service) {
    this(service, MvcStreamOptions.DEFAULT);
  }

  public CatnapMvcStreams(ProjectionService service, MvcStreamOptions options) {
    this.service = Objects.requireNonNull(service);
    this.options = Objects.requireNonNull(options);
    var sequence = new AtomicInteger();
    workers =
        new ThreadPoolExecutor(
            options.workers(),
            options.workers(),
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(options.queuedTasks()),
            runnable -> {
              var thread = new Thread(runnable, "catnap-mvc-stream-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(service.settings().evaluation().maxDepth() + 1)
                            .build())
                    .build())
            .build();
    json = new Jackson3Json(mapper);
  }

  public <T> JsonStreamEncoder<T> encoder(
      HttpServletRequest request, JsonStreamEncoder.Serializer<T> serializer) {
    ProjectionRequest bound = request(request);
    return new JsonStreamEncoder<>(
        serializer,
        bytes -> {
          var selected = bound.evaluate(json.parse(bytes), json.adapter());
          var output = new BoundedBuffer(bound.settings().maxPayloadBytes());
          mapper.writeValue(output, selected);
          return output.toByteArray();
        },
        bound.active(),
        bound.settings().maxPayloadBytes());
  }

  public <T> CatnapMvcEmitter<T> ndjson(
      HttpServletRequest request, HttpServletResponse response, JsonStreamEncoder<T> encoder) {
    var nativeEmitter = new NativeNdjson(request(request), response);
    var emitter =
        new CatnapMvcEmitter<T>(
            nativeEmitter,
            workers,
            options.pendingSends(),
            value -> {
              byte[] json = encoder.ndjson(value);
              byte[] line = Arrays.copyOf(json, json.length + 1);
              line[json.length] = '\n';
              return () -> nativeEmitter.send(line, MediaType.APPLICATION_NDJSON);
            },
            nativeEmitter::completeWithError);
    nativeEmitter.owner = emitter;
    return register(request, emitter);
  }

  /**
   * Owns and closes the input on completion, failure, timeout, cancellation, or service shutdown.
   */
  public <T> CatnapMvcEmitter<String> encodedNdjson(
      HttpServletRequest request,
      HttpServletResponse response,
      InputStream input,
      JsonStreamEncoder<T> encoder) {
    var nativeEmitter = new NativeNdjson(request(request), response);
    var emitter =
        new CatnapMvcEmitter<String>(
            nativeEmitter,
            workers,
            options.pendingSends(),
            value -> {
              byte[] json = encoder.ndjsonEncoded(value);
              byte[] line = Arrays.copyOf(json, json.length + 1);
              line[json.length] = '\n';
              return () -> nativeEmitter.send(line, MediaType.APPLICATION_NDJSON);
            },
            nativeEmitter::completeWithError);
    nativeEmitter.owner = emitter;
    register(request, emitter);
    var reader = new NdjsonReader(input, service.settings().maxPayloadBytes());
    emitter
        .termination()
        .whenComplete(
            (unused, failure) -> {
              try {
                reader.close();
              } catch (IOException ignored) {
              }
            });
    emitter.subscribe(
        (Flow.Publisher<String>)
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      private final java.util.concurrent.atomic.AtomicBoolean stopped =
                          new java.util.concurrent.atomic.AtomicBoolean();

                      @Override
                      public void request(long count) {
                        if (stopped.get()) return;
                        try {
                          if (count != 1)
                            throw new IllegalStateException(
                                "NDJSON input expects one record of demand");
                          String record = reader.nextRecord();
                          if (stopped.get()) return;
                          if (record == null) {
                            cancel();
                            subscriber.onComplete();
                          } else subscriber.onNext(record);
                        } catch (Exception failure) {
                          cancel();
                          subscriber.onError(failure);
                        }
                      }

                      @Override
                      public void cancel() {
                        if (stopped.compareAndSet(false, true))
                          try {
                            reader.close();
                          } catch (IOException ignored) {
                          }
                      }
                    }));
    return emitter;
  }

  public <T> CatnapMvcEmitter<ServerSentEvent<StreamPayload<T>>> sse(
      HttpServletRequest request, HttpServletResponse response, JsonStreamEncoder<T> encoder) {
    return sse(request, response, encoder, null);
  }

  /** Optional protocol error events are bounded, unprojected, and followed by completion. */
  public <T> CatnapMvcEmitter<ServerSentEvent<StreamPayload<T>>> sse(
      HttpServletRequest request,
      HttpServletResponse response,
      JsonStreamEncoder<T> encoder,
      Function<Throwable, ServerSentEvent<String>> onError) {
    var nativeEmitter = new NativeSse(request(request), response);
    var emitter =
        new CatnapMvcEmitter<ServerSentEvent<StreamPayload<T>>>(
            nativeEmitter,
            workers,
            options.pendingSends(),
            event -> {
              encoder.metadata(
                  event.id(),
                  event.event(),
                  event.comment(),
                  event.retry() == null ? null : event.retry().toString());
              String data =
                  event.data() == null
                      ? null
                      : new String(encoder.payload(event.data()), StandardCharsets.UTF_8);
              var prepared = event(event, data);
              return () -> nativeEmitter.send(prepared);
            },
            failure -> {
              try {
                if (response.isCommitted()
                    && onError != null
                    && !(failure instanceof CancellationException)
                    && !executorRejected(failure)) {
                  var error = onError.apply(failure);
                  if (error != null) {
                    var bounds =
                        new JsonStreamEncoder<String>(
                            (value, output) -> {},
                            bytes -> bytes,
                            false,
                            service.settings().maxPayloadBytes());
                    bounds.metadata(
                        error.id(),
                        error.event(),
                        error.comment(),
                        error.retry() == null ? null : error.retry().toString());
                    if (error.data() != null) bounds.payload(StreamPayload.text(error.data()));
                    nativeEmitter.send(event(error, error.data()));
                    nativeEmitter.complete();
                    return;
                  }
                }
                nativeEmitter.completeWithError(failure);
              } catch (IOException disconnected) {
                // The native async error callback owns a failed Servlet write.
              } catch (RuntimeException encodingFailure) {
                nativeEmitter.completeWithError(
                    new IllegalStateException("SSE error event failed", encodingFailure));
              }
            });
    nativeEmitter.owner = emitter;
    return register(request, emitter);
  }

  private static SseEmitter.SseEventBuilder event(ServerSentEvent<?> source, String data) {
    var result = SseEmitter.event();
    if (source.id() != null) result.id(source.id());
    if (source.event() != null) result.name(source.event());
    if (source.retry() != null) result.reconnectTime(source.retry().toMillis());
    if (source.comment() != null) result.comment(source.comment());
    if (data != null) result.data(data, new MediaType("text", "plain", StandardCharsets.UTF_8));
    return result;
  }

  private static boolean executorRejected(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof RejectedExecutionException) return true;
    return false;
  }

  private <T> CatnapMvcEmitter<T> register(
      HttpServletRequest request, CatnapMvcEmitter<T> emitter) {
    if (request.getAttribute(CatnapMvcStreamFilter.INSTALLED) == null)
      throw new IllegalStateException(
          "Register CatnapMvcStreamFilter with async support before returning stream emitters");
    if (request.getAttribute(CatnapMvcStreamFilter.EMITTER) != null)
      throw new IllegalStateException("Only one Catnap emitter may be attached to a response");
    request.setAttribute(CatnapMvcStreamFilter.EMITTER, emitter);
    connections.add(emitter);
    emitter.termination().whenComplete((unused, error) -> connections.remove(emitter));
    return emitter;
  }

  private ProjectionRequest request(HttpServletRequest request) {
    var bound = CatnapMvcInterceptor.projection(request);
    if (bound != null) return bound;
    String[] values = request.getParameterValues(service.settings().parameter());
    try {
      bound = service.bind(values == null ? null : Arrays.asList(values));
      request.setAttribute(CatnapMvcInterceptor.REQUEST_ATTRIBUTE, bound);
      return bound;
    } catch (QueryRequestException invalid) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid partial response query", invalid);
    }
  }

  @Override
  public void close() {
    workers.shutdownNow();
    connections.forEach(
        emitter -> emitter.stop(new CancellationException("Stream service closed")));
  }

  private static void headers(
      ProjectionRequest query, HttpServletResponse servlet, ServerHttpResponse response) {
    if (query.active())
      for (String name : ResponsePolicy.BODY_HEADERS) {
        response.getHeaders().remove(name);
        servlet.setHeader(name, null);
      }
  }

  private final class NativeNdjson extends ResponseBodyEmitter {
    private final ProjectionRequest query;
    private final HttpServletResponse servlet;
    private CatnapMvcEmitter<?> owner;

    NativeNdjson(ProjectionRequest query, HttpServletResponse servlet) {
      super(options.timeoutMillis());
      this.query = query;
      this.servlet = servlet;
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
      response.getHeaders().setContentType(MediaType.APPLICATION_NDJSON);
      headers(query, servlet, response);
      owner.initialized();
    }
  }

  private final class NativeSse extends SseEmitter {
    private final ProjectionRequest query;
    private final HttpServletResponse servlet;
    private CatnapMvcEmitter<?> owner;

    NativeSse(ProjectionRequest query, HttpServletResponse servlet) {
      super(options.timeoutMillis());
      this.query = query;
      this.servlet = servlet;
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
      super.extendResponse(response);
      headers(query, servlet, response);
      owner.initialized();
    }
  }
}
