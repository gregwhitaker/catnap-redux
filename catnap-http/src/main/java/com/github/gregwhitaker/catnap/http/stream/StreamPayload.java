package com.github.gregwhitaker.catnap.http.stream;

import java.util.Objects;

/**
 * Explicit SSE data semantics, independent of a framework's event envelope. Null data is control.
 */
public sealed interface StreamPayload<T> {
  /** A complete typed JSON value. A null value represents JSON null, not absent event data. */
  record Json<T>(T value) implements StreamPayload<T> {}

  /** Already encoded JSON, validated as one complete value even when no query is supplied. */
  record EncodedJson<T>(String value) implements StreamPayload<T> {
    public EncodedJson {
      Objects.requireNonNull(value);
    }
  }

  /** Intentional text is not parsed or projected, even if its contents resemble JSON. */
  record Text<T>(String value) implements StreamPayload<T> {
    public Text {
      Objects.requireNonNull(value);
    }
  }

  static <T> StreamPayload<T> json(T value) {
    return new Json<>(value);
  }

  static <T> StreamPayload<T> encodedJson(String value) {
    return new EncodedJson<>(value);
  }

  static <T> StreamPayload<T> text(String value) {
    return new Text<>(value);
  }
}
