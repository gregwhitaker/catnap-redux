package com.github.gregwhitaker.catnap.core;

/** A malformed expression, with stable code and source coordinates. */
public final class QuerySyntaxException extends IllegalArgumentException {
  /** Version 1 syntax diagnostic codes. */
  public enum Code {
    EMPTY_QUERY,
    EXPECTED_SELECTOR,
    EXPECTED_OPEN_PAREN,
    EXPECTED_CLOSE_PAREN,
    UNEXPECTED_TOKEN,
    UNTERMINATED_NAME,
    INVALID_ESCAPE,
    INVALID_NAME,
    EXPECTED_PREDICATE,
    EXPECTED_OPERATOR,
    EXPECTED_VALUE,
    EXPECTED_CLOSE_BRACKET,
    UNTERMINATED_VALUE,
    INVALID_VALUE
  }

  private final Code code;
  private final SourcePosition position;

  QuerySyntaxException(Code code, String expression, int offset) {
    super(code + " at UTF-16 offset " + offset);
    this.code = code;
    this.position = SourcePosition.at(expression, offset);
  }

  public Code code() {
    return code;
  }

  public SourcePosition position() {
    return position;
  }
}
