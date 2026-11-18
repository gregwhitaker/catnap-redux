package com.github.gregwhitaker.catnap.core;

import java.util.List;
import java.util.Map;

/**
 * Binding to an application's materialized JSON nodes. Implementations must not mutate input nodes.
 * Java null may represent JSON null if supported by the adapter. Containers have unique field names
 * and acyclic children. Shared subtrees are allowed. Thread safety is the adapter's responsibility.
 * Factories receive fresh collections owned by the evaluation and may retain them.
 *
 * @param <N> the native JSON node type
 */
public interface JsonTreeAdapter<N> {
  /** JSON container or scalar (including JSON null). */
  enum Kind {
    OBJECT,
    ARRAY,
    SCALAR
  }

  Kind kind(N node);

  /** Iterate object members in source order. Field names must not be null. */
  Iterable<? extends Map.Entry<String, N>> fields(N object);

  /** Iterate array members in source order. */
  Iterable<N> elements(N array);

  /** Construct a new object from the supplied insertion-ordered fields. */
  N object(Map<String, N> fields);

  /** Construct a new array without changing element order. */
  N array(List<N> elements);

  /**
   * Preserve scalar type/value/precision. Copy mutable scalars; immutable scalars may be shared.
   */
  N copyScalar(N scalar);

  /**
   * Read a scalar for CATNAP predicates, preserving its JSON type and numeric precision. Existing
   * adapters need only implement this when enabling predicates; field-only queries never call it.
   */
  default JsonScalar scalarValue(N scalar) {
    throw new UnsupportedOperationException(
        "This adapter does not support predicate scalar access");
  }
}
