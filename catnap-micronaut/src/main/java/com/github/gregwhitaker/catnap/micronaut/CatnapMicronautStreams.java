package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.streams.NdjsonFramer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Explicit bounded JSON streams using native Micronaut response framing and transport. */
@Singleton
public final class CatnapMicronautStreams {
  static final String ATTRIBUTE = CatnapMicronautStreams.class.getName() + ".stream";
  private static final MediaType NDJSON = MediaType.of("application/x-ndjson");
  private final ProjectionService service;
  private final MicronautJson json;

  public CatnapMicronautStreams(ProjectionService service) {
    this.service = Objects.requireNonNull(service);
    json = new MicronautJson(service.settings().evaluation().maxDepth());
  }

  /**
   * The mapper and Argument carry the application's explicit view, type, and serializer context.
   */
  public <T> JsonStreamEncoder<T> encoder(
      HttpRequest<?> request, JsonMapper mapper, Argument<T> type) {
    return encoder(request, (value, output) -> mapper.writeValue(output, type, value));
  }

  /** Captures one request's query and a native UTF-8 serialization operation. */
  public <T> JsonStreamEncoder<T> encoder(
      HttpRequest<?> request, JsonStreamEncoder.Serializer<T> serializer) {
    ProjectionRequest bound = query(request);
    return new JsonStreamEncoder<>(
        serializer,
        bytes -> json.project(bytes, bound, StandardCharsets.UTF_8),
        bound.active(),
        bound.settings().maxPayloadBytes());
  }

  public <T> MutableHttpResponse<Publisher<byte[]>> ndjson(
      HttpRequest<?> request, Publisher<T> source, JsonStreamEncoder<T> encoder) {
    return records(
        request,
        () ->
            Flux.from(source)
                .map(
                    value -> {
                      try {
                        return encoder.ndjson(value);
                      } catch (IOException error) {
                        throw Exceptions.propagate(error);
                      }
                    }));
  }

  /** Accepts arbitrary bounded UTF-8 input chunks; records need not align with chunk boundaries. */
  public <T> MutableHttpResponse<Publisher<byte[]>> encodedNdjson(
      HttpRequest<?> request, Publisher<byte[]> source, JsonStreamEncoder<T> encoder) {
    int maximum = query(request).settings().maxPayloadBytes();
    return records(
        request,
        () -> {
          var framer = new NdjsonFramer(maximum);
          Flux<byte[]> records =
              Flux.from(source)
                  .concatMap(
                      chunk ->
                          Flux.generate(
                              () -> 0,
                              (Integer offset,
                                  reactor.core.publisher.SynchronousSink<byte[]> sink) -> {
                                try {
                                  while (offset < chunk.length) {
                                    String record = framer.accept(chunk[offset++] & 255);
                                    if (record != null) {
                                      sink.next(encoder.ndjsonEncoded(record));
                                      return offset;
                                    }
                                  }
                                  sink.complete();
                                } catch (Exception failure) {
                                  sink.error(failure);
                                }
                                return offset;
                              }),
                      0);
          return records.concatWith(
              Mono.defer(
                  () -> {
                    try {
                      framer.endOfInput();
                      return Mono.empty();
                    } catch (IOException failure) {
                      return Mono.error(failure);
                    }
                  }));
        });
  }

  private MutableHttpResponse<Publisher<byte[]>> records(
      HttpRequest<?> request, Supplier<Flux<byte[]>> source) {
    query(request);
    Flux<byte[]> body =
        once(
            () ->
                source
                    .get()
                    .map(
                        bytes -> {
                          byte[] line = Arrays.copyOf(bytes, bytes.length + 1);
                          line[bytes.length] = '\n';
                          return line;
                        }));
    return HttpResponse.<Publisher<byte[]>>ok(body).contentType(NDJSON).attribute(ATTRIBUTE, true);
  }

  /** Native Event framing distinguishes intentional text, declared JSON, and control events. */
  public <T> MutableHttpResponse<Publisher<Event<String>>> sse(
      HttpRequest<?> request,
      Publisher<Event<StreamPayload<T>>> source,
      JsonStreamEncoder<T> encoder) {
    return sse(request, source, encoder, null);
  }

  /** After a successful event, an optional bounded protocol error event ends the stream. */
  public <T> MutableHttpResponse<Publisher<Event<String>>> sse(
      HttpRequest<?> request,
      Publisher<Event<StreamPayload<T>>> source,
      JsonStreamEncoder<T> encoder,
      Function<Throwable, Event<String>> onError) {
    query(request);
    Flux<Event<String>> body =
        once(
            () -> {
              var emitted = new AtomicBoolean();
              Flux<Event<String>> events =
                  Flux.from(source)
                      .map(event -> encodeEvent(event, encoder))
                      .doOnNext(event -> emitted.set(true));
              if (onError == null) return events;
              return events.onErrorResume(
                  failure -> {
                    if (!emitted.get()
                        || failure instanceof CancellationException
                        || Exceptions.isCancel(failure)) return Flux.error(failure);
                    Event<String> event = onError.apply(failure);
                    if (event == null) return Flux.error(failure);
                    var bounds =
                        new JsonStreamEncoder<String>(
                            (value, output) -> {},
                            bytes -> bytes,
                            false,
                            service.settings().maxPayloadBytes());
                    return Flux.just(
                        encodeEvent(
                            Event.of(
                                event,
                                event.getData() == null
                                    ? null
                                    : StreamPayload.text(event.getData())),
                            bounds));
                  });
            });
    return HttpResponse.<Publisher<Event<String>>>ok(body)
        .contentType(MediaType.TEXT_EVENT_STREAM_TYPE)
        .attribute(ATTRIBUTE, true);
  }

  private static <T> Event<String> encodeEvent(
      Event<StreamPayload<T>> event, JsonStreamEncoder<T> encoder) {
    try {
      encoder.metadata(
          event.getId(),
          event.getName(),
          event.getComment(),
          event.getRetry() == null ? null : event.getRetry().toString());
      // Micronaut uses empty text for a control frame without a data line.
      String data =
          event.getData() == null
              ? ""
              : new String(encoder.payload(event.getData()), StandardCharsets.UTF_8);
      return Event.of(event, data);
    } catch (IOException failure) {
      throw Exceptions.propagate(failure);
    }
  }

  private ProjectionRequest query(HttpRequest<?> request) {
    ProjectionRequest bound = CatnapFilter.projection(request);
    if (bound != null) return bound;
    try {
      bound = service.bind(request.getParameters().getAll(service.settings().parameter()));
      request.setAttribute(CatnapFilter.ATTRIBUTE, bound);
      return bound;
    } catch (QueryRequestException invalid) {
      var failure =
          new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid partial response query");
      failure.initCause(invalid);
      throw failure;
    }
  }

  /**
   * One response owns one encoder; a second subscriber must not re-use partially consumed state.
   */
  private static <T> Flux<T> once(Supplier<Flux<T>> source) {
    var subscribed = new AtomicBoolean();
    return Flux.defer(
            () -> {
              if (!subscribed.compareAndSet(false, true))
                return Flux.error(new IllegalStateException("Stream response already subscribed"));
              return source.get();
            })
        // Micronaut may request the next body from inside its Netty byte-consumption callback.
        // Return from that callback before a synchronous source can deliver another body; otherwise
        // deferred native byte accounting can keep requesting until direct memory is exhausted.
        // Retain at most one prepared item, preserve signal order, and cancel upstream on overflow.
        .publishOn(Schedulers.parallel(), true, 1)
        .onErrorMap(
            failure -> {
              if (failure instanceof CancellationException || Exceptions.isCancel(failure))
                return failure;
              var error =
                  new HttpStatusException(
                      HttpStatus.INTERNAL_SERVER_ERROR, "JSON stream response failed");
              error.initCause(failure);
              return error;
            });
  }
}
