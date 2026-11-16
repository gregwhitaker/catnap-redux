package com.github.gregwhitaker.catnap.benchmarks.stream;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** A reusable, demand-aware fixed-value source; subscriptions own no payload queue. */
public final class BenchmarkSource<T> implements Flow.Publisher<T> {
  private static final ScheduledExecutorService CLOCK =
      Executors.newScheduledThreadPool(
          2,
          task -> {
            var thread = new Thread(task, "benchmark-pacing");
            thread.setDaemon(true);
            return thread;
          });
  private static final Set<Subscription<?>> ACTIVE = ConcurrentHashMap.newKeySet();
  private static final AtomicBoolean PAUSED = new AtomicBoolean();
  private static final AtomicInteger EMITTING = new AtomicInteger();
  private static final LongAdder EMITTED = new LongAdder();
  private final T value;
  private final int perSecond;
  private final boolean synchronousRequests;

  public BenchmarkSource(T value, int perSecond) {
    this(value, perSecond, false);
  }

  /** Diagnostic mode reproduces the original source's synchronous request/drain behavior. */
  public BenchmarkSource(T value, int perSecond, boolean synchronousRequests) {
    if (value == null || perSecond < 0 || perSecond > 1_000_000)
      throw new IllegalArgumentException();
    this.value = value;
    this.perSecond = perSecond;
    this.synchronousRequests = synchronousRequests;
  }

  @Override
  public void subscribe(Flow.Subscriber<? super T> downstream) {
    var subscription = new Subscription<>(downstream, value, perSecond, synchronousRequests);
    ACTIVE.add(subscription);
    try {
      downstream.onSubscribe(subscription);
      subscription.start();
    } catch (RuntimeException | Error failure) {
      subscription.cancel();
      throw failure;
    }
  }

  public static int activeSubscriptions() {
    return ACTIVE.size();
  }

  public static int emitting() {
    return EMITTING.get();
  }

  public static long emitted() {
    return EMITTED.sum();
  }

  /** Pause production while retaining live subscriptions for a quiescent retained-memory sample. */
  public static void pause(boolean paused) {
    PAUSED.set(paused);
    if (!paused) ACTIVE.forEach(Subscription::resume);
  }

  private static final class Subscription<T> implements Flow.Subscription {
    private final Flow.Subscriber<? super T> downstream;
    private final T value;
    private final int perSecond;
    private final boolean synchronousRequests;
    private final AtomicLong requested = new AtomicLong();
    private final AtomicInteger work = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ScheduledFuture<?> tick;

    private Subscription(
        Flow.Subscriber<? super T> downstream,
        T value,
        int perSecond,
        boolean synchronousRequests) {
      this.downstream = downstream;
      this.value = value;
      this.perSecond = perSecond;
      this.synchronousRequests = synchronousRequests;
    }

    private void start() {
      if (perSecond == 0 || cancelled.get()) return;
      tick =
          CLOCK.scheduleAtFixedRate(this::one, 0, 1_000_000_000L / perSecond, TimeUnit.NANOSECONDS);
      if (cancelled.get()) tick.cancel(false);
    }

    @Override
    public void request(long count) {
      if (cancelled.get()) return;
      if (count <= 0) {
        cancel();
        downstream.onError(new IllegalArgumentException("Positive demand required"));
        return;
      }
      requested.getAndUpdate(
          current -> count >= Long.MAX_VALUE - current ? Long.MAX_VALUE : current + count);
      if (perSecond == 0) {
        if (synchronousRequests) drainSynchronously();
        else schedule();
      }
    }

    private void resume() {
      if (perSecond == 0) {
        if (synchronousRequests) CLOCK.execute(this::drainSynchronously);
        else schedule();
      }
    }

    private void drainSynchronously() {
      if (work.getAndIncrement() != 0) return;
      int missed = 1;
      do {
        while (!cancelled.get() && !PAUSED.get() && requested.get() != 0) one();
        missed = work.addAndGet(-missed);
      } while (missed != 0);
    }

    private void schedule() {
      if (!cancelled.get() && !PAUSED.get() && requested.get() != 0 && work.compareAndSet(0, 1))
        CLOCK.execute(this::drain);
    }

    private void drain() {
      try {
        // Never monopolize the caller's transport event loop, even with reentrant/unbounded demand.
        // There is at most one scheduled drain per subscription and no payload queue.
        for (int batch = 0;
            batch < 32 && !cancelled.get() && !PAUSED.get() && requested.get() != 0;
            batch++) one();
      } finally {
        work.set(0);
        schedule();
      }
    }

    private void one() {
      if (cancelled.get() || PAUSED.get()) return;
      long count;
      do {
        count = requested.get();
        if (count == 0) return;
      } while (!requested.compareAndSet(count, count == Long.MAX_VALUE ? count : count - 1));
      EMITTING.incrementAndGet();
      try {
        if (!cancelled.get() && !PAUSED.get()) {
          EMITTED.increment();
          downstream.onNext(value);
        } else if (!cancelled.get() && count != Long.MAX_VALUE) {
          requested.getAndUpdate(current -> current == Long.MAX_VALUE ? current : current + 1);
        }
      } catch (Throwable failure) {
        cancel();
        downstream.onError(failure);
      } finally {
        EMITTING.decrementAndGet();
      }
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        ACTIVE.remove(this);
        var scheduled = tick;
        if (scheduled != null) scheduled.cancel(false);
      }
    }
  }
}
