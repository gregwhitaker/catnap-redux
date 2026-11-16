package com.github.gregwhitaker.catnap.benchmarks.stream;

import com.sun.management.ThreadMXBean;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Measurement controls for dedicated local benchmark servers, never a production adapter. */
public final class BenchmarkControl {
  private static final Map<String, Object> VALUE = payload();
  private static final BenchmarkSource<Map<String, Object>> FAST =
      new BenchmarkSource<>(VALUE, 0, Boolean.getBoolean("catnap.benchmark.synchronous-source"));
  private static final BenchmarkSource<Map<String, Object>> PACED =
      new BenchmarkSource<>(VALUE, 500);

  private BenchmarkControl() {}

  /**
   * Every connection at a given rate subscribes to the same source instance and immutable value.
   */
  public static BenchmarkSource<Map<String, Object>> source(int rate) {
    return switch (rate) {
      case 0 -> FAST;
      case 500 -> PACED;
      default -> throw new IllegalArgumentException("Benchmark rate must be 0 or 500");
    };
  }

  public static Map<String, Object> payload() {
    // 1,024 bytes in compact UTF-8 JSON with the insertion order below.
    var result = new LinkedHashMap<String, Object>();
    result.put("id", 1);
    result.put("name", "ACME");
    result.put("price", Map.of("amount", 12, "currency", "USD"));
    result.put("padding", "x".repeat(950));
    return java.util.Collections.unmodifiableMap(result);
  }

  public static Map<String, Object> pause(boolean paused) {
    BenchmarkSource.pause(paused);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (paused && BenchmarkSource.emitting() != 0 && System.nanoTime() < deadline)
      LockSupport.parkNanos(1_000_000);
    if (paused && BenchmarkSource.emitting() != 0)
      throw new IllegalStateException("Source did not quiesce");
    return Map.of("paused", paused, "active", BenchmarkSource.activeSubscriptions());
  }

  public static Map<String, Object> metrics(boolean collect) {
    if (collect) {
      long before = collections();
      System.gc();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (collections() <= before && System.nanoTime() < deadline)
        LockSupport.parkNanos(1_000_000);
      if (collections() <= before) throw new IllegalStateException("Explicit GC was not observed");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("heapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    result.put("collections", collections());
    result.put("active", BenchmarkSource.activeSubscriptions());
    result.put("emitted", BenchmarkSource.emitted());
    result.put("javaVersion", System.getProperty("java.runtime.version"));
    var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!threads.isThreadAllocatedMemorySupported())
      throw new IllegalStateException("Allocation counters unavailable");
    if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
    long allocated;
    try {
      // Added in Java 21. Benchmark servers run on 25; the fixture bytecode remains Java 17.
      // Unlike summing live threads, this includes allocations from threads that have terminated.
      allocated =
          (long) ThreadMXBean.class.getMethod("getTotalThreadAllocatedBytes").invoke(threads);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Use Java 25 for streaming measurements", failure);
    }
    if (allocated < 0) throw new IllegalStateException("Global allocation counter unavailable");
    result.put("allocatedBytes", allocated);
    result.put("liveThreads", threads.getThreadCount());
    long direct =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
            .filter(pool -> pool.getName().equals("direct"))
            .mapToLong(BufferPoolMXBean::getMemoryUsed)
            .sum();
    result.put("jvmDirectBytes", direct);
    try {
      Class<?> allocator = Class.forName("io.netty.buffer.PooledByteBufAllocator");
      Object instance = allocator.getField("DEFAULT").get(null);
      Object metric = allocator.getMethod("metric").invoke(instance);
      long pooled = (long) metric.getClass().getMethod("usedDirectMemory").invoke(metric);
      Class<?> platform = Class.forName("io.netty.util.internal.PlatformDependent");
      long global = (long) platform.getMethod("usedDirectMemory").invoke(null);
      result.put("nettyPooledDirectBytes", pooled);
      result.put("nettyDirectBytes", global >= 0 ? global : pooled);
      result.put("nettyDirectCounter", global >= 0 ? "global" : "pooled-fallback");
    } catch (ClassNotFoundException absent) {
      result.put("nettyDirectBytes", 0);
      result.put("nettyPooledDirectBytes", 0);
      result.put("nettyDirectCounter", "not-present");
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot inspect native allocator", failure);
    }
    return result;
  }

  private static long collections() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
        .sum();
  }
}
