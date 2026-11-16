package com.github.gregwhitaker.catnap.testkit.probe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Example-only connection gates and counters; tests register and remove their own keys. */
public final class StreamLifecycleProbe {
  public static final ConcurrentHashMap<String, StreamLifecycleProbe> CONNECTIONS =
      new ConcurrentHashMap<>();
  public final CompletableFuture<Void> release = new CompletableFuture<>();
  public final CompletableFuture<Void> finished = new CompletableFuture<>();
  public final AtomicInteger emitted = new AtomicInteger();
}
