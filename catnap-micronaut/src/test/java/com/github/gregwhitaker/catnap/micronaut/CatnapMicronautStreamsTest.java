package com.github.gregwhitaker.catnap.micronaut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.util.context.Context;

class CatnapMicronautStreamsTest {
  private final CatnapMicronautStreams streams =
      new CatnapMicronautStreams(new ProjectionService(ProjectionSettings.DEFAULT));

  @Test
  void oneRecordOfDemandAllowsOnlyOnePreparedItemAndCancellationReachesTheSource()
      throws InterruptedException {
    var request = HttpRequest.GET("/");
    request.getParameters().add("fields", "id");
    var encoded = new AtomicInteger();
    var cancelled = new AtomicBoolean();
    var source = Flux.range(0, 100_000).doOnCancel(() -> cancelled.set(true));
    var encoder =
        streams.<Integer>encoder(
            request,
            (value, output) -> {
              encoded.incrementAndGet();
              output.write(
                  ("{\"id\":" + value + ",\"other\":42}").getBytes(StandardCharsets.UTF_8));
            });
    var response = streams.ndjson(request, source, encoder);
    var received = new CopyOnWriteArrayList<String>();
    var subscriber = firstRecord(received);
    Flux.from(response.body()).subscribe(subscriber);
    await(() -> received.size() == 1 && encoded.get() == 2);
    assertEquals(List.of("{\"id\":0}\n"), received);
    assertEquals(2, encoded.get());
    subscriber.cancel();
    assertTrue(cancelled.get());
    assertEquals(2, encoded.get());
  }

  @Test
  void manyRecordsInOneChunkAreFramedOnlyAsDemandArrives() throws InterruptedException {
    var request = HttpRequest.GET("/");
    request.getParameters().add("fields", "id");
    var encoder = streams.<String>encoder(request, (value, output) -> fail("Already encoded"));
    var response =
        streams.encodedNdjson(
            request,
            Flux.just(("{\"id\":1}\n".repeat(10_000)).getBytes(StandardCharsets.UTF_8)),
            encoder);
    var records = new CopyOnWriteArrayList<String>();
    var subscriber = firstRecord(records);
    Flux.from(response.body()).subscribe(subscriber);
    await(() -> records.size() == 1);
    assertEquals(List.of("{\"id\":1}\n"), records);
    subscriber.request(1);
    await(() -> records.size() == 2);
    assertEquals(2, records.size());
    subscriber.cancel();
  }

  @Test
  void reactorContextIsPreservedAndAResponseRejectsASecondSubscription() {
    var request = HttpRequest.GET("/");
    var encoder =
        streams.<String>encoder(
            request,
            (value, output) ->
                output.write(("\"" + value + "\"").getBytes(StandardCharsets.UTF_8)));
    var response =
        streams.ndjson(
            request,
            Flux.deferContextual(context -> Flux.just(context.<String>get("label"))),
            encoder);
    var values =
        Flux.from(response.body())
            .contextWrite(Context.of("label", "context"))
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .collectList()
            .block();
    assertEquals(List.of("\"context\"\n"), values);
    assertThrows(HttpStatusException.class, () -> Flux.from(response.body()).blockLast());
  }

  @Test
  void declaredJsonIsValidatedWithoutAQueryButTextAndControlFramesArePreserved() {
    var request = HttpRequest.GET("/");
    var encoder = streams.<String>encoder(request, (value, output) -> fail("Explicit payload"));
    var response =
        streams.sse(
            request,
            Flux.just(
                Event.of(StreamPayload.<String>text("{invalid")),
                Event.of(Event.of(""), (StreamPayload<String>) null).comment("heartbeat")),
            encoder);
    var events = Flux.from(response.body()).collectList().block();
    assertEquals("{invalid", events.get(0).getData());
    assertEquals("", events.get(1).getData());
    assertEquals("heartbeat", events.get(1).getComment());
    var invalid =
        streams.sse(
            request, Flux.just(Event.of(StreamPayload.<String>encodedJson("{invalid"))), encoder);
    var failure =
        assertThrows(HttpStatusException.class, () -> Flux.from(invalid.body()).blockLast());
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.getStatus());
    assertEquals("JSON stream response failed", failure.getMessage());
  }

  @Test
  void aPushSourceCanRejectOverflowWithoutLeavingItsProducerRunning() throws InterruptedException {
    var request = HttpRequest.GET("/");
    var sink = new AtomicReference<FluxSink<Integer>>();
    var stopped = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    var received = new AtomicInteger();
    Flux<Integer> source =
        Flux.create(
            emitter -> {
              emitter.onDispose(() -> stopped.set(true));
              sink.set(emitter);
            },
            FluxSink.OverflowStrategy.ERROR);
    var encoder =
        streams.<Integer>encoder(
            request,
            (value, output) -> output.write(value.toString().getBytes(StandardCharsets.UTF_8)));
    var subscriber =
        new BaseSubscriber<byte[]>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            request(1);
          }

          @Override
          protected void hookOnNext(byte[] value) {
            received.incrementAndGet();
          }

          @Override
          protected void hookOnError(Throwable error) {
            failure.set(error);
          }
        };
    Flux.from(streams.ndjson(request, source, encoder).body()).subscribe(subscriber);
    sink.get().next(1);
    await(() -> received.get() == 1 && sink.get().requestedFromDownstream() == 1);
    sink.get().next(2);
    sink.get().next(3);
    assertTrue(stopped.get());
    // The already prepared second item precedes the terminal error, without keeping the producer
    // alive while the consumer is slow.
    subscriber.request(1);
    await(() -> failure.get() != null);
    assertEquals(2, received.get());
    assertInstanceOf(HttpStatusException.class, failure.get());
  }

  @Test
  void synchronousDemandReturnsBeforeTheNextNativeWriteCallback() throws Exception {
    var request = HttpRequest.GET("/");
    var encoder =
        streams.<Integer>encoder(
            request,
            (value, output) -> output.write(value.toString().getBytes(StandardCharsets.UTF_8)));
    var writeStarted = new CountDownLatch(1);
    var finishWrite = new CountDownLatch(1);
    var writeFinished = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    var deliveredThread = new AtomicReference<Thread>();
    var subscribedThread = Thread.currentThread();
    var subscriber =
        new BaseSubscriber<byte[]>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            request(1);
          }

          @Override
          protected void hookOnNext(byte[] value) {
            deliveredThread.set(Thread.currentThread());
            writeStarted.countDown();
            try {
              finishWrite.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              writeFinished.countDown();
            }
          }
        };
    try {
      Flux.from(
              streams
                  .ndjson(
                      request,
                      Flux.range(0, 100_000).doOnCancel(() -> cancelled.set(true)),
                      encoder)
                  .body())
          .subscribe(subscriber);
      assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
      assertNotSame(subscribedThread, deliveredThread.get());
      assertEquals(1, writeFinished.getCount(), "Subscription must not wait for the native write");
      subscriber.cancel();
      assertTrue(cancelled.get(), "Cancellation reaches the source during a pending write");
    } finally {
      finishWrite.countDown();
      subscriber.cancel();
    }
  }

  @Test
  void aDisabledProjectionStillBoundsExplicitStreamsAndBypassesQueryParsing() {
    var defaults = ProjectionSettings.DEFAULT;
    var disabled =
        new CatnapMicronautStreams(
            new ProjectionService(
                new ProjectionSettings(
                    false,
                    defaults.parameter(),
                    defaults.dialect(),
                    defaults.compilation(),
                    defaults.evaluation(),
                    0,
                    8)));
    var request = HttpRequest.GET("/");
    request.getParameters().add("fields", "(");
    var encoder =
        disabled.<String>encoder(
            request, (value, output) -> output.write(value.getBytes(StandardCharsets.UTF_8)));
    var body = disabled.ndjson(request, Flux.just("123456789"), encoder).body();
    assertThrows(HttpStatusException.class, () -> Flux.from(body).blockLast());
  }

  @Test
  void invalidQueriesFailBeforeAResponseCanSubscribeToItsSource() {
    var request = HttpRequest.GET("/");
    request.getParameters().add("fields", "(");
    var failure =
        assertThrows(
            HttpStatusException.class,
            () -> streams.encoder(request, (value, output) -> fail("Not serialized")));
    assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
  }

  @Test
  void protocolErrorEventsAreBoundedAndNeverRecoverCancellation() {
    var defaults = ProjectionSettings.DEFAULT;
    var bounded =
        new CatnapMicronautStreams(
            new ProjectionService(
                new ProjectionSettings(
                    true,
                    defaults.parameter(),
                    defaults.dialect(),
                    defaults.compilation(),
                    defaults.evaluation(),
                    0,
                    8)));
    var request = HttpRequest.GET("/");
    for (boolean metadata : List.of(false, true)) {
      var encoder = bounded.<String>encoder(request, (value, output) -> fail("Text payload"));
      var first = Event.of(StreamPayload.<String>text("ok"));
      var response =
          bounded.sse(
              request,
              Flux.just(first).concatWith(Flux.error(new IllegalStateException("source failure"))),
              encoder,
              error -> metadata ? Event.of("x").comment("x".repeat(9)) : Event.of("x".repeat(9)));
      var received = new ArrayList<Event<String>>();
      assertThrows(
          HttpStatusException.class,
          () -> Flux.from(response.body()).doOnNext(received::add).blockLast());
      assertEquals(1, received.size());
      assertEquals("ok", received.get(0).getData());
    }
    var recovered = new AtomicBoolean();
    var encoder = bounded.<String>encoder(request, (value, output) -> fail("Text payload"));
    var response =
        bounded.sse(
            request,
            Flux.just(Event.of(StreamPayload.<String>text("ok")))
                .concatWith(
                    Flux.error(new java.util.concurrent.CancellationException("cancelled"))),
            encoder,
            error -> {
              recovered.set(true);
              return Event.of("error");
            });
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> Flux.from(response.body()).blockLast());
    assertFalse(recovered.get());
  }

  private static BaseSubscriber<byte[]> firstRecord(List<String> records) {
    return new BaseSubscriber<>() {
      @Override
      protected void hookOnSubscribe(Subscription subscription) {
        request(1);
      }

      @Override
      protected void hookOnNext(byte[] value) {
        records.add(new String(value, StandardCharsets.UTF_8));
      }
    };
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(condition.getAsBoolean(), "Asynchronous stream did not make expected progress");
  }
}
