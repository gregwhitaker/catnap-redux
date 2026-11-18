package com.github.gregwhitaker.catnap.core;

import java.math.BigDecimal;

/**
 * An immutable JSON scalar for predicate comparisons. The value is a String, Boolean, BigDecimal,
 * or null. Numbers must retain their precision; strings are never coerced to other JSON types.
 */
public record JsonScalar(Object value) {
  public static final JsonScalar NULL = new JsonScalar(null);

  public JsonScalar {
    if (value != null
        && !(value instanceof String)
        && !(value instanceof Boolean)
        && !(value instanceof BigDecimal)) {
      throw new IllegalArgumentException("Expected String, Boolean, BigDecimal, or null");
    }
  }
}
