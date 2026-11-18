package com.github.gregwhitaker.catnap.http;

import com.github.gregwhitaker.catnap.core.CompileOptions;
import com.github.gregwhitaker.catnap.core.EvaluationOptions;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import java.util.Objects;

/** Immutable common settings mapped by each framework's native configuration facilities. */
public record ProjectionSettings(
    boolean enabled,
    String parameter,
    QueryDialect dialect,
    CompileOptions compilation,
    EvaluationOptions evaluation,
    int cacheCapacity,
    int maxPayloadBytes) {
  public static final ProjectionSettings DEFAULT =
      new ProjectionSettings(
          true,
          "fields",
          QueryDialect.CATNAP,
          CompileOptions.DEFAULT,
          EvaluationOptions.DEFAULT,
          256,
          1_048_576);

  public ProjectionSettings {
    Objects.requireNonNull(parameter);
    Objects.requireNonNull(dialect);
    Objects.requireNonNull(compilation);
    Objects.requireNonNull(evaluation);
    if (parameter.isBlank() || cacheCapacity < 0 || maxPayloadBytes < 1) {
      throw new IllegalArgumentException(
          "A parameter name, nonnegative cache capacity, and positive payload limit are required");
    }
  }
}
