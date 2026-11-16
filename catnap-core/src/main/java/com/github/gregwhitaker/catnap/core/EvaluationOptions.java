package com.github.gregwhitaker.catnap.core;

/** Per-evaluation traversal bounds. Root depth is zero; work includes selector matching. */
public record EvaluationOptions(int maxDepth, long maxWork) {
  /** Initial bounds; tune for the application's payload sizes and workload. */
  public static final EvaluationOptions DEFAULT = new EvaluationOptions(128, 1_000_000);

  public EvaluationOptions {
    if (maxDepth < 0 || maxDepth > 256 || maxWork < 1) {
      throw new IllegalArgumentException("Depth must be 0..256 and work must be positive");
    }
  }
}
