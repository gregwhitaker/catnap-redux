package com.github.gregwhitaker.catnap.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuarkusStreamSubscriberTest {
  static final class Wire implements QuarkusStreamSubscriber.Transport<Integer> {
    final List<Integer> items = new ArrayList<>();
    final List<CompletableFuture<Void>> writes = new ArrayList<>();
    boolean completed;
    Throwable failure;
    boolean wireFailure;

    @Override
    public CompletionStage<?> write(Integer item) {
      items.add(item);
      var ack = new CompletableFuture<Void>();
      writes.add(ack);
      return ack;
    }

    @Override
    public void complete() {
      completed = true;
    }

    @Override
    public void fail(Throwable error, boolean transportFailure) {
      failure = error;
      wireFailure = transportFailure;
    }
  }

  @Test
  void noNextDemandOrCompletionUntilTheCurrentNativeWriteFinishes() {
    var wire = new Wire();
    var subscriber = new QuarkusStreamSubscriber<>(wire);
    var requests = new AtomicInteger();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            assertEquals(1, n);
            int count = requests.getAndIncrement();
            subscriber.onNext(count);
            if (count == 1) subscriber.onComplete();
          }

          @Override
          public void cancel() {
            fail("Successful source");
          }
        });
    assertEquals(List.of(0), wire.items);
    assertEquals(1, requests.get());
    assertFalse(wire.completed);
    wire.writes.get(0).complete(null);
    assertEquals(List.of(0, 1), wire.items);
    assertFalse(wire.completed);
    wire.writes.get(1).complete(null);
    assertTrue(wire.completed);
    assertEquals(2, requests.get());
  }

  @Test
  void aSourceFailureCancelsImmediatelyButWaitsForAnOutstandingWrite() {
    var wire = new Wire();
    var subscriber = new QuarkusStreamSubscriber<>(wire);
    var cancelled = new AtomicBoolean();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            subscriber.onNext(0);
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    var expected = new IllegalStateException("source failed");
    subscriber.onError(expected);
    assertTrue(cancelled.get());
    assertNull(wire.failure);
    wire.writes.get(0).complete(null);
    assertSame(expected, wire.failure);
    assertFalse(wire.wireFailure);
    assertFalse(wire.completed);
  }

  @Test
  void aNativeWriteFailureCancelsAndIsNotAnApplicationErrorEvent() {
    var wire = new Wire();
    var subscriber = new QuarkusStreamSubscriber<>(wire);
    var cancelled = new AtomicBoolean();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            subscriber.onNext(0);
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    var expected = new IllegalStateException("transport failed");
    wire.writes.get(0).completeExceptionally(expected);
    assertTrue(cancelled.get());
    assertSame(expected, wire.failure);
    assertTrue(wire.wireFailure);
  }

  @Test
  void overflowIsRejectedWithoutQueuingOrDiscardingAcceptedOutput() {
    var wire = new Wire();
    var subscriber = new QuarkusStreamSubscriber<>(wire);
    var cancelled = new AtomicBoolean();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            subscriber.onNext(0);
            subscriber.onNext(1);
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    assertTrue(cancelled.get());
    assertEquals(List.of(0), wire.items);
    wire.writes.get(0).complete(null);
    assertInstanceOf(RejectedExecutionException.class, wire.failure);
  }

  @Test
  void aDisconnectedClientCancelsAndIgnoresLateAcknowledgments() {
    var wire = new Wire();
    var subscriber = new QuarkusStreamSubscriber<>(wire);
    var cancelled = new AtomicBoolean();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            subscriber.onNext(0);
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    subscriber.cancel();
    wire.writes.get(0).complete(null);
    subscriber.onError(new IllegalStateException("late error"));
    assertTrue(cancelled.get());
    assertNull(wire.failure);
    assertFalse(wire.completed);
    assertEquals(1, wire.items.size());
  }

  @Test
  void synchronousSourcesAndNativeAcknowledgmentsDoNotGrowTheStack() {
    var count = new AtomicInteger();
    var completed = new AtomicBoolean();
    var subscriber =
        new QuarkusStreamSubscriber<Integer>(
            new QuarkusStreamSubscriber.Transport<>() {
              @Override
              public CompletionStage<?> write(Integer value) {
                return CompletableFuture.completedFuture(null);
              }

              @Override
              public void complete() {
                completed.set(true);
              }

              @Override
              public void fail(Throwable error, boolean transportFailure) {
                org.junit.jupiter.api.Assertions.fail(error);
              }
            });
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
    assertTrue(completed.get());
    assertEquals(50_001, count.get());
  }
}
