package com.github.gregwhitaker.catnap.spring.webflux;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.streams.NdjsonFramer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.ServerSentEventHttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/** Explicit per-subscription JSON streaming, with native WebFlux SSE framing and transport. */
public final class CatnapWebFluxStreams {
  private final ProjectionService service;
  private final JsonMapper mapper;
  private final Jackson3Json json;

  public CatnapWebFluxStreams(ProjectionService service) {
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
    json = new Jackson3Json(mapper);
  }

  /** Captures the endpoint's query and UTF-8 native serializer, including type/view/attributes. */
  public <T> JsonStreamEncoder<T> encoder(
      ServerWebExchange exchange, JsonStreamEncoder.Serializer<T> serializer) {
    ProjectionRequest request = request(exchange);
    return new JsonStreamEncoder<>(
        serializer,
        bytes -> {
          var selected = request.evaluate(json.parse(bytes), json.adapter());
          var output = new BoundedBuffer(request.settings().maxPayloadBytes());
          mapper.writeValue(output, selected);
          return output.toByteArray();
        },
        request.active(),
        request.settings().maxPayloadBytes());
  }

  /** Each source item is one compact JSON record; demand and Reactor context remain attached. */
  public <T> Mono<Void> writeNdjson(
      ServerWebExchange exchange, Publisher<T> source, JsonStreamEncoder<T> encoder) {
    return writeRecords(
        exchange,
        Flux.from(source)
            .map(
                value -> {
                  try {
                    return encoder.ndjson(value);
                  } catch (IOException failure) {
                    throw Exceptions.propagate(failure);
                  }
                }));
  }

  /**
   * Frames arbitrary UTF-8 chunks incrementally, without collecting a chunk's records into a list.
   */
  public <T> Mono<Void> writeEncodedNdjson(
      ServerWebExchange exchange, Publisher<byte[]> source, JsonStreamEncoder<T> encoder) {
    return Mono.defer(
        () -> {
          var framer = new NdjsonFramer(request(exchange).settings().maxPayloadBytes());
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
          return writeRecords(
              exchange,
              records.concatWith(
                  Mono.defer(
                      () -> {
                        try {
                          framer.endOfInput();
                          return Mono.empty();
                        } catch (IOException failure) {
                          return Mono.error(failure);
                        }
                      })));
        });
  }

  private Mono<Void> writeRecords(ServerWebExchange exchange, Flux<byte[]> records) {
    return Mono.defer(
            () -> {
              prepare(exchange);
              var response = exchange.getResponse();
              response.getHeaders().setContentType(MediaType.APPLICATION_NDJSON);
              Flux<Publisher<? extends DataBuffer>> groups =
                  records.map(
                      bytes -> {
                        byte[] line = Arrays.copyOf(bytes, bytes.length + 1);
                        line[bytes.length] = '\n';
                        return Mono.fromSupplier(
                            () -> (DataBuffer) DefaultDataBufferFactory.sharedInstance.wrap(line));
                      });
              return response.writeAndFlushWith(paced(groups));
            })
        .onErrorMap(CatnapWebFluxStreams::publicFailure);
  }

  public <T> Mono<Void> writeSse(
      ServerWebExchange exchange,
      Publisher<ServerSentEvent<StreamPayload<T>>> source,
      JsonStreamEncoder<T> encoder) {
    return writeSse(exchange, source, encoder, null);
  }

  /** A configured protocol error event is bounded and unprojected; it ends a committed stream. */
  public <T> Mono<Void> writeSse(
      ServerWebExchange exchange,
      Publisher<ServerSentEvent<StreamPayload<T>>> source,
      JsonStreamEncoder<T> encoder,
      Function<Throwable, ServerSentEvent<String>> onError) {
    return Mono.defer(
            () -> {
              prepare(exchange);
              Flux<ServerSentEvent<String>> events =
                  Flux.from(source).map(event -> encodeEvent(event, encoder));
              if (onError != null)
                events =
                    events.onErrorResume(
                        failure -> {
                          if (!exchange.getResponse().isCommitted() || isCancellation(failure))
                            return Flux.error(failure);
                          var error = onError.apply(failure);
                          if (error == null) return Flux.error(failure);
                          var bounds =
                              new JsonStreamEncoder<String>(
                                  (value, out) -> {},
                                  bytes -> bytes,
                                  false,
                                  request(exchange).settings().maxPayloadBytes());
                          try {
                            bounds.metadata(
                                error.id(),
                                error.event(),
                                error.comment(),
                                error.retry() == null ? null : error.retry().toString());
                            if (error.data() != null)
                              bounds.payload(StreamPayload.text(error.data()));
                            return Flux.just(error);
                          } catch (Exception invalid) {
                            return Flux.error(invalid);
                          }
                        });
              var response =
                  new ServerHttpResponseDecorator(exchange.getResponse()) {
                    // Native SSE framing may allocate a complete frame before its inner publisher
                    // is subscribed.
                    // Heap buffers need no reference-count cleanup when an unopened frame is
                    // cancelled.
                    @Override
                    public DataBufferFactory bufferFactory() {
                      return DefaultDataBufferFactory.sharedInstance;
                    }

                    @Override
                    public Mono<Void> writeAndFlushWith(
                        Publisher<? extends Publisher<? extends DataBuffer>> body) {
                      return super.writeAndFlushWith(paced(body));
                    }
                  };
              return new ServerSentEventHttpMessageWriter()
                  .write(
                      events,
                      ResolvableType.forClassWithGenerics(ServerSentEvent.class, String.class),
                      MediaType.TEXT_EVENT_STREAM,
                      response,
                      Map.of());
            })
        .onErrorMap(CatnapWebFluxStreams::publicFailure);
  }

  private static <T> ServerSentEvent<String> encodeEvent(
      ServerSentEvent<StreamPayload<T>> event, JsonStreamEncoder<T> encoder) {
    try {
      encoder.metadata(
          event.id(),
          event.event(),
          event.comment(),
          event.retry() == null ? null : event.retry().toString());
      String data =
          event.data() == null
              ? null
              : new String(encoder.payload(event.data()), StandardCharsets.UTF_8);
      var result = ServerSentEvent.<String>builder();
      if (data != null) result.data(data);
      if (event.id() != null) result.id(event.id());
      if (event.event() != null) result.event(event.event());
      if (event.retry() != null) result.retry(event.retry());
      if (event.comment() != null) result.comment(event.comment());
      return result.build();
    } catch (IOException failure) {
      throw Exceptions.propagate(failure);
    }
  }

  private ProjectionRequest request(ServerWebExchange exchange) {
    ProjectionRequest bound = CatnapWebFluxHandlerAdapter.projection(exchange);
    if (bound != null) return bound;
    try {
      bound =
          service.bind(exchange.getRequest().getQueryParams().get(service.settings().parameter()));
      exchange.getAttributes().put(CatnapWebFluxHandlerAdapter.ATTRIBUTE, bound);
      return bound;
    } catch (QueryRequestException invalid) {
      throw new QueryBindingFailure(invalid);
    }
  }

  private void prepare(ServerWebExchange exchange) {
    if (request(exchange).active()) {
      ResponsePolicy.BODY_HEADERS.forEach(exchange.getResponse().getHeaders()::remove);
      exchange
          .getResponse()
          .beforeCommit(
              () -> {
                ResponsePolicy.BODY_HEADERS.forEach(exchange.getResponse().getHeaders()::remove);
                return Mono.empty();
              });
    }
  }

  /** No source prefetch: request another logical group only after the current group is consumed. */
  static Flux<Publisher<? extends DataBuffer>> paced(
      Publisher<? extends Publisher<? extends DataBuffer>> groups) {
    return Flux.from(groups)
        .concatMap(
            group -> {
              Sinks.One<Void> consumed = Sinks.one();
              Publisher<? extends DataBuffer> body =
                  Flux.from(group)
                      .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                      .doFinally(signal -> consumed.tryEmitEmpty());
              return Flux.concat(Mono.just(body), consumed.asMono().then(Mono.empty()));
            },
            0);
  }

  private static Throwable publicFailure(Throwable failure) {
    if (failure instanceof QueryBindingFailure || isCancellation(failure)) return failure;
    return new IllegalStateException("JSON stream response failed", failure);
  }

  private static boolean isCancellation(Throwable failure) {
    return Exceptions.isCancel(failure)
        || failure instanceof java.util.concurrent.CancellationException;
  }

  private static final class QueryBindingFailure extends ResponseStatusException {
    QueryBindingFailure(QueryRequestException cause) {
      super(HttpStatus.BAD_REQUEST, "Invalid partial response query", cause);
    }
  }
}
