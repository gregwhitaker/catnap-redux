package com.github.gregwhitaker.catnap.benchmarks.webflux;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Optional bounded diagnostics for the benchmark server; never part of a production adapter. */
@Component
final class StreamTrace implements WebFilter {
  private static final boolean ENABLED = Boolean.getBoolean("catnap.benchmark.trace");
  private static final Map<String, Trace> RECENT = new LinkedHashMap<>();

  static <T> Flux<T> source(String id, Flux<T> source) {
    Trace trace;
    synchronized (RECENT) {
      trace = RECENT.get(id);
    }
    if (trace == null) return source;
    return source
        .doOnNext(value -> trace.source.increment())
        .doFinally(signal -> trace.terminal = signal.name());
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String id = exchange.getRequest().getQueryParams().getFirst("trace");
    if (!ENABLED || id == null) return chain.filter(exchange);
    var trace = new Trace();
    synchronized (RECENT) {
      RECENT.put(id, trace);
      while (RECENT.size() > 128) RECENT.remove(RECENT.keySet().iterator().next());
    }
    var response =
        new ServerHttpResponseDecorator(exchange.getResponse()) {
          @Override
          public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return super.writeWith(Flux.from(body).doOnNext(trace::buffer));
          }

          @Override
          public Mono<Void> writeAndFlushWith(
              Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(
                Flux.from(body)
                    .map(
                        group ->
                            Flux.from(group)
                                .doOnSubscribe(subscription -> trace.groupsStarted.increment())
                                .doOnNext(trace::buffer)
                                .doFinally(signal -> trace.groupsFinished.increment())));
          }
        };
    return chain.filter(exchange.mutate().response(response).build());
  }

  static Map<String, Object> snapshot(boolean threads) {
    var result = new LinkedHashMap<String, Object>();
    synchronized (RECENT) {
      RECENT.forEach((id, trace) -> result.put(id, trace.snapshot()));
    }
    if (threads)
      result.put(
          "threads",
          Thread.getAllStackTraces().entrySet().stream()
              .limit(128)
              .map(
                  entry ->
                      Map.of(
                          "name", entry.getKey().getName(),
                          "state", entry.getKey().getState().name(),
                          "stack",
                              Arrays.stream(entry.getValue())
                                  .limit(40)
                                  .map(Object::toString)
                                  .toList()))
              .toList());
    return result;
  }

  private static final class Trace {
    final LongAdder source = new LongAdder();
    final LongAdder bytes = new LongAdder();
    final LongAdder lineBreaks = new LongAdder();
    final LongAdder groupsStarted = new LongAdder();
    final LongAdder groupsFinished = new LongAdder();
    volatile String terminal = "open";

    void buffer(DataBuffer buffer) {
      int end = buffer.readPosition() + buffer.readableByteCount();
      int lines = 0;
      for (int i = buffer.readPosition(); i < end; i++) if (buffer.getByte(i) == '\n') lines++;
      bytes.add(buffer.readableByteCount());
      lineBreaks.add(lines);
    }

    Map<String, Object> snapshot() {
      return Map.of(
          "source", source.sum(),
          "bytes", bytes.sum(),
          "lineBreaks", lineBreaks.sum(),
          "groupsStarted", groupsStarted.sum(),
          "groupsFinished", groupsFinished.sum(),
          "sourceTerminal", terminal);
    }
  }
}
