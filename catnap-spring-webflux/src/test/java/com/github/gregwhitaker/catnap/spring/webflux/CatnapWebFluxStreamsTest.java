package com.github.gregwhitaker.catnap.spring.webflux;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

class CatnapWebFluxStreamsTest {
  @Test
  void transportPrefetchCannotPrepareAnotherGroupUntilTheCurrentGroupIsConsumed() {
    var count = new AtomicInteger();
    var cancelled = new AtomicBoolean();
    var received = new ArrayList<Publisher<? extends DataBuffer>>();
    Flux<Publisher<? extends DataBuffer>> source =
        Flux.range(0, 100_000)
            .map(
                i -> {
                  count.incrementAndGet();
                  return Mono.just(
                      (DataBuffer) DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {1}));
                });
    var pending =
        CatnapWebFluxStreams.paced(source.doOnCancel(() -> cancelled.set(true)))
            .subscribe(received::add);
    assertEquals(1, count.get());
    assertEquals(1, received.size());
    Flux.from(received.get(0)).doOnNext(DataBufferUtils::release).blockLast();
    assertEquals(2, count.get());
    pending.dispose();
    assertTrue(cancelled.get());
    assertEquals(2, count.get());
  }

  @Test
  void pushSourcesCanRejectOverflowWithoutIntroducingAnAdapterQueue() {
    var pushed = new AtomicInteger();
    var stopped = new AtomicBoolean();
    var rejected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var received = new ArrayList<Publisher<? extends DataBuffer>>();
    Flux<Publisher<? extends DataBuffer>> source =
        Flux.create(
            sink -> {
              sink.onDispose(() -> stopped.set(true));
              while (!stopped.get() && pushed.incrementAndGet() < 100_000)
                sink.next(Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {1})));
            },
            FluxSink.OverflowStrategy.ERROR);
    CatnapWebFluxStreams.paced(source).subscribe(received::add, rejected::set);
    assertEquals(2, pushed.get());
    assertEquals(1, received.size());
    assertNotNull(rejected.get(), "A push that cannot slow down gets an explicit overflow error");
    assertTrue(stopped.get(), "Source disposal also runs on overflow failure");
  }

  @Test
  void explicitFunctionalIntegrationValidatesQueriesAndRetainsReactorContext() {
    var streams = new CatnapWebFluxStreams(new ProjectionService(ProjectionSettings.DEFAULT));
    var invalid =
        MockServerWebExchange.from(MockServerHttpRequest.get("/").queryParam("fields", "("));
    var failure =
        assertThrows(
            ResponseStatusException.class, () -> streams.encoder(invalid, (value, output) -> {}));
    assertEquals(400, failure.getStatusCode().value());
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/?fields=id"));
    var encoder =
        streams.<String>encoder(
            exchange, (value, output) -> output.write(value.getBytes(StandardCharsets.UTF_8)));
    streams
        .writeNdjson(
            exchange,
            Flux.deferContextual(
                context ->
                    Flux.just("{\"id\":\"" + context.get("connection") + "\",\"private\":1}")),
            encoder)
        .contextWrite(context -> context.put("connection", "retained"))
        .block();
    assertEquals("{\"id\":\"retained\"}\n", exchange.getResponse().getBodyAsString().block());
  }

  @Test
  void globalDisablementBypassesQueryBindingButExplicitStreamsStillHaveByteBounds() {
    var defaults = ProjectionSettings.DEFAULT;
    var settings =
        new ProjectionSettings(
            false,
            defaults.parameter(),
            defaults.dialect(),
            defaults.compilation(),
            defaults.evaluation(),
            0,
            16);
    var streams = new CatnapWebFluxStreams(new ProjectionService(settings));
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/").queryParam("fields", "("));
    var encoder =
        streams.<String>encoder(
            exchange, (value, out) -> out.write(value.getBytes(StandardCharsets.UTF_8)));
    streams.writeNdjson(exchange, Flux.just("{\"id\":1}"), encoder).block();
    assertEquals("{\"id\":1}\n", exchange.getResponse().getBodyAsString().block());
    assertThrows(java.io.IOException.class, () -> encoder.json("x".repeat(17)));
  }

  @Test
  void failureBeforeFirstRecordLeavesTheResponseUncommittedAndDoesNotExposeParserInput() {
    var streams = new CatnapWebFluxStreams(new ProjectionService(ProjectionSettings.DEFAULT));
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/?fields=id"));
    var encoder =
        streams.<String>encoder(
            exchange, (value, output) -> output.write(value.getBytes(StandardCharsets.UTF_8)));
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                streams
                    .writeNdjson(exchange, Flux.just("{\"private\":\"secret\","), encoder)
                    .block());
    assertEquals("JSON stream response failed", failure.getMessage());
    assertFalse(exchange.getResponse().isCommitted());
  }
}
