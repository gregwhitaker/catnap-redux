package com.github.gregwhitaker.catnap.spring.webflux;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

class CatnapWebFluxStreamsTest {
  @Test
  void aDrainedWriteWindowLeavesNoUnrequestedRecordAtTheSource() {
    var prepared = new ArrayList<Integer>();
    var delivered = new ArrayList<Integer>();
    var groups = new ArrayList<Publisher<? extends DataBuffer>>();
    var completed = new AtomicBoolean();
    var transport =
        new BaseSubscriber<Publisher<? extends DataBuffer>>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {}

          @Override
          protected void hookOnNext(Publisher<? extends DataBuffer> group) {
            groups.add(group);
          }
        };
    try {
      CatnapWebFluxStreams.paced(
              Flux.range(1, 3)
                  .map(
                      number -> {
                        prepared.add(number);
                        return Mono.fromSupplier(
                            () ->
                                DefaultDataBufferFactory.sharedInstance.wrap(
                                    new byte[] {number.byteValue()}));
                      }))
          .doOnComplete(() -> completed.set(true))
          .subscribe(transport);
      assertTrue(prepared.isEmpty());
      for (int number = 1; number <= 3; number++) {
        transport.request(1);
        assertEquals(number, groups.size());
        assertFalse(completed.get());
        Flux.from(groups.get(number - 1))
            .doOnNext(
                buffer -> {
                  delivered.add((int) buffer.read());
                  DataBufferUtils.release(buffer);
                })
            .blockLast();
        assertEquals(
            delivered,
            prepared,
            "Draining the granted window must account for every source record");
      }
      assertEquals(List.of(1, 2, 3), delivered);
      assertTrue(completed.get(), "Completion must not need another request");
    } finally {
      transport.dispose();
    }
  }

  @Test
  void transportPrefetchOnlyPullsTheNextRecordWhenItsWriteGroupIsRead() {
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
    assertEquals(2, received.size(), "Transport can prefetch the next flush group");
    assertEquals(1, count.get(), "An unopened write group must not pull a source record");
    var slowWriter =
        new BaseSubscriber<DataBuffer>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {}

          @Override
          protected void hookOnNext(DataBuffer buffer) {
            DataBufferUtils.release(buffer);
          }
        };
    Flux.from(received.get(1)).subscribe(slowWriter);
    assertEquals(1, count.get(), "Opening the group must wait for buffer demand");
    slowWriter.request(1);
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
