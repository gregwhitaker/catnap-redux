package com.github.gregwhitaker.catnap.benchmarks.stream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BenchmarkSourceTest {
  private final List<Probe> probes = new ArrayList<>();

  @AfterEach
  void close() {
    probes.forEach(probe -> probe.subscription.cancel());
    BenchmarkSource.pause(false);
    assertEquals(0, BenchmarkSource.activeSubscriptions());
  }

  @Test
  void repeatedSubscriptionsHaveIndependentDemandAndCancel() throws Exception {
    var source = new BenchmarkSource<>("value", 0);
    var first = subscribe(source);
    var second = subscribe(source);
    first.subscription.request(3);
    second.subscription.request(5);
    awaitCount(first, 3);
    awaitCount(second, 5);
    assertEquals(3, first.count.get());
    assertEquals(5, second.count.get());
    first.subscription.cancel();
    first.subscription.request(1);
    second.subscription.request(2);
    awaitCount(second, 7);
    assertEquals(3, first.count.get());
    assertEquals(7, second.count.get());
    assertEquals(1, BenchmarkSource.activeSubscriptions());
  }

  @Test
  void reentrantDemandIsStackSafeAndCancellable() throws Exception {
    var source = new BenchmarkSource<>("value", 0);
    var probe =
        new Probe() {
          @Override
          public void onNext(String item) {
            super.onNext(item);
            if (count.get() == 50_000) subscription.cancel();
            else subscription.request(1);
          }
        };
    probes.add(probe);
    source.subscribe(probe);
    probe.subscription.request(1);
    awaitCount(probe, 50_000);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (BenchmarkSource.activeSubscriptions() != 0 && System.nanoTime() < deadline)
      Thread.sleep(1);
    assertEquals(50_000, probe.count.get());
    assertEquals(0, BenchmarkSource.activeSubscriptions());
  }

  @Test
  void pausingKeepsPendingDemandAndResumesWithoutBlockingControl() throws Exception {
    BenchmarkSource.pause(true);
    var probe = subscribe(new BenchmarkSource<>("value", 0));
    probe.subscription.request(Long.MAX_VALUE);
    assertEquals(0, probe.count.get());
    BenchmarkSource.pause(false);
    assertTrue(probe.first.await(5, TimeUnit.SECONDS));
    BenchmarkControl.pause(true);
    int paused = probe.count.get();
    Thread.sleep(20);
    assertEquals(paused, probe.count.get());
  }

  @Test
  void synchronousDiagnosticModeRunsOnRequestThreadWithStackSafeDemand() {
    var caller = Thread.currentThread();
    var probe =
        new Probe() {
          @Override
          public void onNext(String item) {
            assertSame(caller, Thread.currentThread());
            super.onNext(item);
            if (count.get() == 50_000) subscription.cancel();
            else subscription.request(1);
          }
        };
    probes.add(probe);
    new BenchmarkSource<>("value", 0, true).subscribe(probe);
    probe.subscription.request(1);
    assertEquals(50_000, probe.count.get());
    assertNull(probe.failure);
    assertEquals(0, BenchmarkSource.activeSubscriptions());
  }

  @Test
  void unboundedDemandDoesNotOccupyTheRequestingTransportThread() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var probe =
        new Probe() {
          @Override
          public void onNext(String item) {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
              super.onNext(item);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          }
        };
    probes.add(probe);
    new BenchmarkSource<>("value", 0).subscribe(probe);
    try {
      probe.subscription.request(Long.MAX_VALUE);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertNull(probe.failure, "onNext must not time out before request returns");
      assertEquals(0, probe.count.get(), "request must return while onNext is still blocked");
      probe.subscription.cancel();
    } finally {
      release.countDown();
    }
  }

  @Test
  void pacingDoesNotEmitWithoutDemand() throws Exception {
    var probe = subscribe(new BenchmarkSource<>("value", 500));
    probe.subscription.request(1);
    assertTrue(probe.first.await(5, TimeUnit.SECONDS));
    Thread.sleep(20);
    assertEquals(1, probe.count.get());
    assertNull(probe.failure);
  }

  @Test
  void invalidDemandTerminatesAndDeregisters() {
    var probe = subscribe(new BenchmarkSource<>("value", 0));
    probe.subscription.request(0);
    assertInstanceOf(IllegalArgumentException.class, probe.failure);
    assertEquals(0, BenchmarkSource.activeSubscriptions());
  }

  @Test
  void metricsUseGlobalAllocationAndObservedCollection() {
    var metrics = BenchmarkControl.metrics(true);
    assertTrue(((Number) metrics.get("allocatedBytes")).longValue() > 0);
    assertTrue(((Number) metrics.get("heapBytes")).longValue() > 0);
    assertTrue(((Number) metrics.get("collections")).longValue() > 0);
    assertEquals(0, metrics.get("active"));
    assertSame(BenchmarkControl.source(0), BenchmarkControl.source(0));
    assertSame(BenchmarkControl.source(500), BenchmarkControl.source(500));
  }

  @Test
  void concurrentSourcesHaveExactCountersAtEveryPausedBoundary() throws Exception {
    BenchmarkControl.pause(true);
    long baseline = BenchmarkSource.emitted();
    var source = new BenchmarkSource<>("value", 0);
    var first = subscribe(source);
    var second = subscribe(source);
    first.subscription.request(Long.MAX_VALUE);
    second.subscription.request(Long.MAX_VALUE);
    for (int boundary = 0; boundary < 100; boundary++) {
      BenchmarkControl.pause(false);
      Thread.sleep(1);
      BenchmarkControl.pause(true);
      assertEquals(
          BenchmarkSource.emitted() - baseline,
          (long) first.count.get() + second.count.get(),
          "Every counted emission must have returned from onNext before a paused snapshot");
    }
  }

  private Probe subscribe(BenchmarkSource<String> source) {
    var probe = new Probe();
    probes.add(probe);
    source.subscribe(probe);
    return probe;
  }

  private static void awaitCount(Probe probe, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (probe.count.get() < expected && System.nanoTime() < deadline) Thread.sleep(1);
    assertEquals(expected, probe.count.get());
  }

  private static class Probe implements Flow.Subscriber<String> {
    Flow.Subscription subscription;
    final AtomicInteger count = new AtomicInteger();
    final CountDownLatch first = new CountDownLatch(1);
    volatile Throwable failure;

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
    }

    @Override
    public void onNext(String item) {
      count.incrementAndGet();
      first.countDown();
    }

    @Override
    public void onError(Throwable error) {
      failure = error;
    }

    @Override
    public void onComplete() {
      fail("The benchmark source is unbounded");
    }
  }
}
