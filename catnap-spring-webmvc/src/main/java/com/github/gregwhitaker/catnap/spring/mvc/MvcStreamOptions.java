package com.github.gregwhitaker.catnap.spring.mvc;

/** Application-wide worker/task limits and per-connection pending-send/timeout limits. */
public record MvcStreamOptions(int workers, int queuedTasks, int pendingSends, long timeoutMillis) {
  public static final MvcStreamOptions DEFAULT = new MvcStreamOptions(4, 64, 16, 30_000);

  public MvcStreamOptions {
    if (workers < 1 || queuedTasks < 1 || pendingSends < 1 || timeoutMillis < 0)
      throw new IllegalArgumentException(
          "Positive worker/queue bounds and a nonnegative timeout are required");
  }
}
