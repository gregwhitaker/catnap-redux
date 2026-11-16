package com.github.gregwhitaker.catnap.core;

import java.util.List;
import java.util.Map;

/** Test-only JSON model using ordinary Java containers and immutable scalar values. */
class PlainTreeAdapter implements JsonTreeAdapter<Object> {
  static final PlainTreeAdapter INSTANCE = new PlainTreeAdapter();

  @Override
  public Kind kind(Object value) {
    return value instanceof Map ? Kind.OBJECT : value instanceof List ? Kind.ARRAY : Kind.SCALAR;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Iterable<? extends Map.Entry<String, Object>> fields(Object object) {
    return ((Map<String, Object>) object).entrySet();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Iterable<Object> elements(Object array) {
    return (List<Object>) array;
  }

  @Override
  public Object object(Map<String, Object> fields) {
    return fields;
  }

  @Override
  public Object array(List<Object> elements) {
    return elements;
  }

  @Override
  public Object copyScalar(Object value) {
    return value;
  }
}
