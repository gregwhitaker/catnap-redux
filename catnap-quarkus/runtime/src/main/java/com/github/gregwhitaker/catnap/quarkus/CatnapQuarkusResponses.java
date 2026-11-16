package com.github.gregwhitaker.catnap.quarkus;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json;
import com.github.gregwhitaker.catnap.streams.NdjsonFramer;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Function;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;

/** Explicit response boundaries for paths that bypass Quarkus's ordinary response filters. */
@Singleton
public final class CatnapQuarkusResponses {
  static final String PROJECTED = CatnapQuarkusResponses.class.getName() + ".projected";
  private final ProjectionService service;
  private final JsonMapper mapper;
  private final Jackson2Json json;

  @Inject
  public CatnapQuarkusResponses(ProjectionService service) {
    this.service = Objects.requireNonNull(service);
    mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(service.settings().evaluation().maxDepth() + 1)
                            .build())
                    .build())
            .build();
    json = new Jackson2Json(mapper);
  }

  /**
   * Collects serialized items under one total document bound, then projects the complete array. The
   * serializer must retain the application's declared type, view, and writer customizations.
   */
  public <T> Uni<Response> jsonArray(
      ServerRequestContext request,
      Flow.Publisher<T> source,
      JsonStreamEncoder.Serializer<T> serializer) {
    var context = (ResteasyReactiveRequestContext) request;
    ProjectionRequest query = query(context);
    return collect(query, source, serializer)
        .map(
            bytes -> {
              // Identify this exact entity so a subsequent response filter does not project it
              // twice.
              context.setProperty(PROJECTED, bytes);
              return Response.ok(bytes, MediaType.APPLICATION_JSON_TYPE).build();
            });
  }

  <T> Uni<byte[]> collect(
      ProjectionRequest query,
      Flow.Publisher<T> source,
      JsonStreamEncoder.Serializer<T> serializer) {
    return Uni.createFrom()
        .emitter(
            emitter -> {
              var collector =
                  new FiniteJsonSubscriber<>(
                      serializer,
                      query.settings().maxPayloadBytes(),
                      bytes -> {
                        try {
                          if (query.active()) {
                            var selected = query.evaluate(json.parse(bytes), json.adapter());
                            var output = new BoundedBuffer(query.settings().maxPayloadBytes());
                            mapper.writeValue(output, selected);
                            bytes = output.toByteArray();
                          }
                          emitter.complete(bytes);
                        } catch (IOException | RuntimeException failure) {
                          emitter.fail(publicFailure(failure));
                        }
                      },
                      failure -> emitter.fail(publicFailure(failure)));
              emitter.onTermination(collector::cancel);
              try {
                source.subscribe(collector);
              } catch (RuntimeException failure) {
                collector.onError(failure);
              }
            });
  }

  /** Captures one connection's query and the complete native serialization context. */
  public <T> JsonStreamEncoder<T> encoder(
      ServerRequestContext request, JsonStreamEncoder.Serializer<T> serializer) {
    var query = query((ResteasyReactiveRequestContext) request);
    return new JsonStreamEncoder<>(
        serializer,
        bytes -> {
          var selected = query.evaluate(json.parse(bytes), json.adapter());
          var output = new BoundedBuffer(query.settings().maxPayloadBytes());
          mapper.writeValue(output, selected);
          return output.toByteArray();
        },
        query.active(),
        query.settings().maxPayloadBytes());
  }

  public <T> CatnapStream ndjson(
      ServerRequestContext request, Flow.Publisher<T> source, JsonStreamEncoder<T> encoder) {
    var records =
        Multi.createFrom()
            .publisher(source)
            .map(
                value -> {
                  try {
                    return new CatnapStream.JsonRecord(encoder.ndjson(value));
                  } catch (IOException error) {
                    throw new UncheckedIOException(error);
                  }
                });
    return new CatnapStream(
        query((ResteasyReactiveRequestContext) request),
        MediaType.valueOf("application/x-ndjson"),
        records,
        null);
  }

  public <T> CatnapStream encodedNdjson(
      ServerRequestContext request, Flow.Publisher<byte[]> chunks, JsonStreamEncoder<T> encoder) {
    var query = query((ResteasyReactiveRequestContext) request);
    Multi<CatnapStream.JsonRecord> records =
        Multi.createFrom()
            .deferred(
                () -> {
                  var framer = new NdjsonFramer(query.settings().maxPayloadBytes());
                  var framed =
                      Multi.createFrom()
                          .publisher(chunks)
                          .onItem()
                          .transformToMulti(
                              chunk ->
                                  Multi.createFrom()
                                      .iterable(
                                          () ->
                                              new Iterator<CatnapStream.JsonRecord>() {
                                                int offset;
                                                String next;

                                                @Override
                                                public boolean hasNext() {
                                                  if (next != null) return true;
                                                  try {
                                                    while (offset < chunk.length) {
                                                      next = framer.accept(chunk[offset++] & 255);
                                                      if (next != null) return true;
                                                    }
                                                    return false;
                                                  } catch (IOException failure) {
                                                    throw new UncheckedIOException(failure);
                                                  }
                                                }

                                                @Override
                                                public CatnapStream.JsonRecord next() {
                                                  if (!hasNext())
                                                    throw new NoSuchElementException();
                                                  String record = next;
                                                  next = null;
                                                  try {
                                                    return new CatnapStream.JsonRecord(
                                                        encoder.ndjsonEncoded(record));
                                                  } catch (IOException failure) {
                                                    throw new UncheckedIOException(failure);
                                                  }
                                                }
                                              }))
                          .concatenate(false);
                  return Multi.createBy()
                      .concatenating()
                      .streams(
                          framed,
                          Multi.createFrom()
                              .deferred(
                                  () -> {
                                    try {
                                      framer.endOfInput();
                                      return Multi.createFrom().empty();
                                    } catch (IOException failure) {
                                      return Multi.createFrom().failure(failure);
                                    }
                                  }));
                });
    return new CatnapStream(query, MediaType.valueOf("application/x-ndjson"), records, null);
  }

  public <T> CatnapStream sse(
      ServerRequestContext request,
      Flow.Publisher<OutboundSseEvent> source,
      JsonStreamEncoder<T> encoder) {
    return sse(request, source, encoder, null);
  }

  /** The optional protocol error event contains bounded text and is sent only after commitment. */
  public <T> CatnapStream sse(
      ServerRequestContext request,
      Flow.Publisher<OutboundSseEvent> source,
      JsonStreamEncoder<T> encoder,
      Function<Throwable, OutboundSseEvent> onError) {
    var query = query((ResteasyReactiveRequestContext) request);
    var events =
        Multi.createFrom().publisher(source).map(event -> encodeEvent(event, encoder, false));
    return new CatnapStream(
        query,
        MediaType.SERVER_SENT_EVENTS_TYPE,
        events,
        onError == null
            ? null
            : failure -> {
              var event = onError.apply(failure);
              if (event == null) return null;
              var bounds =
                  new JsonStreamEncoder<String>(
                      (value, output) -> {},
                      bytes -> bytes,
                      false,
                      query.settings().maxPayloadBytes());
              return encodeEvent(event, bounds, true);
            });
  }

  @SuppressWarnings("unchecked")
  private static <T> CatnapStream.SseFrame encodeEvent(
      OutboundSseEvent event, JsonStreamEncoder<T> encoder, boolean errorEvent) {
    try {
      encoder.metadata(
          event.getId(),
          event.getName(),
          event.getComment(),
          event.isReconnectDelaySet() ? Long.toString(event.getReconnectDelay()) : null);
      Object data = event.getData();
      byte[] bytes = null;
      if (data != null) {
        StreamPayload<T> payload;
        if (errorEvent) {
          if (!(data instanceof String text))
            throw new IllegalArgumentException("Protocol error events require text data");
          payload = StreamPayload.text(text);
        } else {
          if (!(data instanceof StreamPayload<?>))
            throw new IllegalArgumentException("Use an explicit JSON or text StreamPayload");
          payload = (StreamPayload<T>) data;
        }
        bytes = encoder.payload(payload);
        if (payload instanceof StreamPayload.Text<?>) {
          // Native SSE formatting treats CR and LF independently; normalize SSE line endings first.
          bytes =
              new String(bytes, StandardCharsets.UTF_8)
                  .replace("\r\n", "\n")
                  .replace('\r', '\n')
                  .getBytes(StandardCharsets.UTF_8);
        }
      }
      return new CatnapStream.SseFrame(
          new CatnapStreamHandler.EncodedEvent(event, bytes, event.getComment()));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private ProjectionRequest query(ResteasyReactiveRequestContext context) {
    var bound = (ProjectionRequest) context.getProperty(CatnapRequestFilter.PROJECTION);
    if (bound != null) return bound;
    try {
      bound =
          service.bind(
              context.getUriInfo().getQueryParameters().get(service.settings().parameter()));
      context.setProperty(CatnapRequestFilter.PROJECTION, bound);
      return bound;
    } catch (QueryRequestException invalid) {
      throw new BadRequestException("Invalid partial response query", invalid);
    }
  }

  private static Throwable publicFailure(Throwable failure) {
    return new InternalServerErrorException("JSON response projection failed", failure);
  }
}
