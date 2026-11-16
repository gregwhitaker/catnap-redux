package com.github.gregwhitaker.catnap.core;

/** An adapter failed while reading or constructing a JSON tree. */
public final class EvaluationException extends RuntimeException {
  /** Evaluation diagnostics other than explicit resource limits. */
  public enum Code {
    ADAPTER_FAILURE
  }

  private final String path;

  EvaluationException(String path, RuntimeException cause) {
    super("JSON adapter failed at " + (path.isEmpty() ? "<root>" : path), cause);
    this.path = path;
  }

  public Code code() {
    return Code.ADAPTER_FAILURE;
  }

  /** JSON Pointer to the node whose adapter operation failed. */
  public String path() {
    return path;
  }
}
