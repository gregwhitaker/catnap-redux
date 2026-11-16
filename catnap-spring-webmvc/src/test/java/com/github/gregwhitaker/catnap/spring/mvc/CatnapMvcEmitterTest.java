package com.github.gregwhitaker.catnap.spring.mvc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

class CatnapMvcEmitterTest {
  private static final class Tasks implements Executor {
    final ArrayDeque<Runnable> queued = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      queued.add(task);
    }

    void next() {
      queued.remove().run();
    }

    void drain() {
      while (!queued.isEmpty()) next();
    }
  }

  @Test
  void earlySendsHaveABoundAndWaitForNativeInitializationBeforeWriting() {
    var tasks = new Tasks();
    var sent = new ArrayList<Integer>();
    var emitter =
        new CatnapMvcEmitter<Integer>(
            new ResponseBodyEmitter(),
            tasks,
            2,
            value -> () -> sent.add(value),
            failure -> fail(failure));
    var first = emitter.send(1).toCompletableFuture();
    var second = emitter.send(2).toCompletableFuture();
    var overflow = emitter.send(3).toCompletableFuture();
    assertInstanceOf(
        RejectedExecutionException.class,
        assertThrows(CompletionException.class, overflow::join).getCause());
    emitter.complete();
    emitter.start();
    assertTrue(tasks.queued.isEmpty());
    assertFalse(first.isDone());
    emitter.initialized();
    emitter.start();
    assertEquals(1, tasks.queued.size());
    tasks.next();
    assertTrue(first.isDone());
    assertFalse(second.isDone());
    tasks.drain();
    assertEquals(List.of(1, 2), sent);
    emitter.termination().toCompletableFuture().join();
  }

  @Test
  void publisherSubscriptionAndDemandWaitForInitializationAndEachFlushedSend() {
    var tasks = new Tasks();
    var sent = new ArrayList<Integer>();
    var demand = new ArrayList<Long>();
    var cancelled = new AtomicBoolean();
    var emitter =
        new CatnapMvcEmitter<Integer>(
            new ResponseBodyEmitter(),
            tasks,
            2,
            value -> () -> sent.add(value),
            failure -> fail(failure));
    emitter.subscribe(
        (org.reactivestreams.Publisher<Integer>)
            subscriber ->
                subscriber.onSubscribe(
                    new org.reactivestreams.Subscription() {
                      int value;

                      @Override
                      public void request(long count) {
                        demand.add(count);
                        subscriber.onNext(++value);
                        if (value == 2) subscriber.onComplete();
                      }

                      @Override
                      public void cancel() {
                        cancelled.set(true);
                      }
                    }));
    assertTrue(demand.isEmpty());
    emitter.initialized();
    emitter.start();
    tasks.next();
    assertEquals(List.of(1L), demand);
    assertTrue(sent.isEmpty());
    tasks.next();
    assertEquals(List.of(1L, 1L), demand);
    assertEquals(List.of(1), sent);
    tasks.drain();
    assertEquals(List.of(1, 2), sent);
    assertTrue(cancelled.get());
  }

  @Test
  void disconnectCancelsUpstreamAndReleasesPendingSends() {
    var tasks = new Tasks();
    var cancelled = new AtomicBoolean();
    var emitter =
        new CatnapMvcEmitter<Integer>(
            new ResponseBodyEmitter(),
            tasks,
            2,
            value -> () -> fail("Disconnected item must not be sent"),
            failure -> {});
    emitter.subscribe(
        (Flow.Publisher<Integer>)
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long count) {
                        subscriber.onNext(1);
                      }

                      @Override
                      public void cancel() {
                        cancelled.set(true);
                      }
                    }));
    emitter.initialized();
    emitter.start();
    tasks.next();
    emitter.stop(new IOException("Disconnected"));
    tasks.drain();
    assertTrue(cancelled.get());
    assertThrows(
        CompletionException.class, () -> emitter.termination().toCompletableFuture().join());
  }

  @Test
  void executorRejectionFailsTheEmitterWithoutStartingOrQueueingMoreWork() {
    var failure = new AtomicReference<Throwable>();
    var emitter =
        new CatnapMvcEmitter<Integer>(
            new ResponseBodyEmitter(),
            task -> {
              throw new RejectedExecutionException();
            },
            2,
            value -> () -> fail("Rejected work"),
            failure::set);
    var pending = emitter.send(1).toCompletableFuture();
    emitter.initialized();
    emitter.start();
    assertEquals("JSON stream response failed", failure.get().getMessage());
    assertThrows(CompletionException.class, pending::join);
  }
}
