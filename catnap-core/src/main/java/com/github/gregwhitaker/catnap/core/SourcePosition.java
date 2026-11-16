package com.github.gregwhitaker.catnap.core;

/** Zero-based UTF-16 offset and one-based line/UTF-16 column in the untrimmed query. */
public record SourcePosition(int offset, int line, int column) {
  static SourcePosition at(String expression, int offset) {
    int line = 1;
    int column = 1;
    for (int i = 0; i < offset; i++) {
      char c = expression.charAt(i);
      if (c == '\r') {
        line++;
        column = 1;
      } else if (c == '\n') {
        if (i == 0 || expression.charAt(i - 1) != '\r') line++;
        column = 1;
      } else column++;
    }
    return new SourcePosition(offset, line, column);
  }
}
