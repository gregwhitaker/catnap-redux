package com.github.gregwhitaker.catnap.core;

/** Compilation bounds, counting UTF-16 units, selector depth, and parsed selector segments. */
public record CompileOptions(int maxQueryLength, int maxDepth, int maxSelectors) {
  /** Conservative starting bounds; not latency guarantees. */
  public static final CompileOptions DEFAULT = new CompileOptions(16_384, 64, 4_096);

  public CompileOptions {
    if (maxQueryLength < 1 || maxDepth < 1 || maxDepth > 256 || maxSelectors < 1) {
      throw new IllegalArgumentException("Positive bounds required; query depth must be <= 256");
    }
  }
}
