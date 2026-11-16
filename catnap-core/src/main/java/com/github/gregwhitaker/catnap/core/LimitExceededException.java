package com.github.gregwhitaker.catnap.core;

/** A configured bound was exceeded; no partially compiled query or result is returned. */
public final class LimitExceededException extends RuntimeException {
  /** Compilation and evaluation limit identifiers. */
  public enum Limit {
    QUERY_LENGTH,
    QUERY_DEPTH,
    SELECTORS,
    JSON_DEPTH,
    EVALUATION_WORK
  }

  private final Limit limit;
  private final long maximum;
  private final long actual;
  private final SourcePosition position;
  private final String path;

  LimitExceededException(
      Limit limit, long maximum, long actual, SourcePosition position, String path) {
    super(limit + " exceeds " + maximum + " (observed " + actual + ")");
    this.limit = limit;
    this.maximum = maximum;
    this.actual = actual;
    this.position = position;
    this.path = path;
  }

  public Limit limit() {
    return limit;
  }

  public long maximum() {
    return maximum;
  }

  public long actual() {
    return actual;
  }

  /** Compilation source location, or null for evaluation limits. */
  public SourcePosition position() {
    return position;
  }

  /** Evaluation JSON Pointer, or null for compilation limits. The root pointer is empty. */
  public String path() {
    return path;
  }
}
