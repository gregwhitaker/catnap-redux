package com.github.gregwhitaker.catnap.http;

import com.github.gregwhitaker.catnap.core.SourcePosition;

/** Invalid HTTP selection; adapters must map this to HTTP 400 before committing the response. */
public final class QueryRequestException extends IllegalArgumentException {
  private final String code;
  private final SourcePosition position;

  QueryRequestException(String code, SourcePosition position, RuntimeException cause) {
    super(code, cause);
    this.code = code;
    this.position = position;
  }

  public String code() {
    return code;
  }

  /** Null for parameter cardinality errors, otherwise coordinates in the decoded expression. */
  public SourcePosition position() {
    return position;
  }
}
