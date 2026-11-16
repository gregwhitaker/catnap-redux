package com.github.gregwhitaker.catnap.core;

import java.util.Objects;

/** An immutable plan reusable concurrently across documents and independent stream payloads. */
public final class CompiledQuery {
  private final Selection selection;

  CompiledQuery(Selection selection) {
    this.selection = selection;
  }

  public Selection selection() {
    return selection;
  }

  public <N> N evaluate(N document, JsonTreeAdapter<N> adapter) {
    return evaluate(document, adapter, EvaluationOptions.DEFAULT);
  }

  public <N> N evaluate(N document, JsonTreeAdapter<N> adapter, EvaluationOptions options) {
    return new Evaluator<>(Objects.requireNonNull(adapter), Objects.requireNonNull(options))
        .evaluate(document, selection);
  }
}
