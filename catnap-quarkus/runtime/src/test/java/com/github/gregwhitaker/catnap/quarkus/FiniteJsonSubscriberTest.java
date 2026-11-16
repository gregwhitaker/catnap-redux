package com.github.gregwhitaker.catnap.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class FiniteJsonSubscriberTest {
  @Test
  void synchronousOneItemRequestsDoNotRecurseOrRetainAListOfObjects() {
    var result = new AtomicReference<byte[]>();
    var count = new AtomicInteger();
    var subscriber =
        new FiniteJsonSubscriber<Integer>(
            (value, output) -> output.write('0'), 100_001, result::set, error -> fail(error));
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            assertEquals(1, n);
            if (count.getAndIncrement() < 50_000) subscriber.onNext(0);
            else subscriber.onComplete();
          }

          @Override
          public void cancel() {
            fail("Successful source");
          }
        });
    assertEquals(100_001, result.get().length);
    assertEquals('[', result.get()[0]);
    assertEquals(']', result.get()[100_000]);
    assertEquals(50_001, count.get());
  }

  @Test
  void theBoundIncludesDelimitersAndCancelsDuringNativeSerialization() {
    var cancelled = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    var count = new AtomicInteger();
    var subscriber =
        new FiniteJsonSubscriber<Integer>(
            (value, output) -> output.write("1234".getBytes(StandardCharsets.UTF_8)),
            10,
            bytes -> fail("Oversized document"),
            failure::set);
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            assertEquals(1, n);
            count.incrementAndGet();
            subscriber.onNext(0);
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    assertInstanceOf(IOException.class, failure.get());
    assertTrue(cancelled.get());
    assertEquals(3, count.get());
  }

  @Test
  void cancellationBeforeSubscriptionRequestsNothingAndErrorsAreIgnored() {
    var cancelled = new AtomicBoolean();
    var subscriber =
        new FiniteJsonSubscriber<Integer>(
            (value, output) -> fail("Cancelled"),
            10,
            bytes -> fail("Cancelled"),
            error -> fail("Cancelled"));
    subscriber.cancel();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            fail("Cancelled before subscription");
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    subscriber.onError(new IllegalStateException("Late source error"));
    subscriber.onComplete();
    assertTrue(cancelled.get());
  }

  @Test
  void cancellationDuringSerializationStopsFurtherRequestsAndSuppressesTheResult()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    var requests = new AtomicInteger();
    var subscriber =
        new FiniteJsonSubscriber<Integer>(
            (value, output) -> {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
              }
              output.write('1');
            },
            10,
            bytes -> fail("Cancelled"),
            error -> fail("Cancelled"));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var work =
          executor.submit(
              () ->
                  subscriber.onSubscribe(
                      new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                          requests.incrementAndGet();
                          subscriber.onNext(1);
                        }

                        @Override
                        public void cancel() {
                          cancelled.set(true);
                        }
                      }));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      subscriber.cancel();
      assertTrue(cancelled.get());
      release.countDown();
      work.get(5, TimeUnit.SECONDS);
      assertEquals(1, requests.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void anEmptyArrayStillRequiresSpaceForBothBrackets() {
    var result = new AtomicReference<byte[]>();
    var failure = new AtomicReference<Throwable>();
    for (int bound : new int[] {1, 2}) {
      var subscriber =
          new FiniteJsonSubscriber<Integer>(
              (value, output) -> fail("Empty"), bound, result::set, failure::set);
      subscriber.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long n) {
              subscriber.onComplete();
            }

            @Override
            public void cancel() {}
          });
      if (bound == 1) assertInstanceOf(IOException.class, failure.get());
      else assertEquals("[]", new String(result.get(), StandardCharsets.UTF_8));
    }
  }
}
