package com.github.gregwhitaker.catnap.core;

import static com.github.gregwhitaker.catnap.core.QuerySyntaxException.Code.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Shared lexer with separate recursive-descent selection productions for each dialect. */
final class QueryParser {
  private final String source;
  private final QueryDialect dialect;
  private final CompileOptions options;
  private int cursor;
  private int selectors;

  QueryParser(String source, QueryDialect dialect, CompileOptions options) {
    validateArguments(source, dialect, options);
    this.source = source;
    this.dialect = dialect;
    this.options = options;
  }

  static void validateArguments(String source, QueryDialect dialect, CompileOptions options) {
    Objects.requireNonNull(source, "expression");
    Objects.requireNonNull(dialect, "dialect");
    Objects.requireNonNull(options, "options");
    if (source.length() > options.maxQueryLength()) {
      throw new LimitExceededException(
          LimitExceededException.Limit.QUERY_LENGTH,
          options.maxQueryLength(),
          source.length(),
          SourcePosition.at(source, options.maxQueryLength()),
          null);
    }
  }

  Selection parse() {
    whitespace();
    if (end()) throw error(EMPTY_QUERY, cursor);
    Builder result = list(0, false);
    return result.freeze();
  }

  private Builder list(int depth, boolean child) {
    Builder result = new Builder();
    while (true) {
      Builder next = dialect == QueryDialect.GOOGLE ? google(depth) : linkedin(depth);
      result.merge(next);
      whitespace();
      if (take(',')) continue;
      if (child) {
        if (take(')')) return result;
        if (end()) throw error(EXPECTED_CLOSE_PAREN, cursor);
      } else if (end()) return result;
      throw error(UNEXPECTED_TOKEN, cursor);
    }
  }

  private Builder google(int depth) {
    List<String> path = new ArrayList<>();
    do {
      path.add(segment(depth + path.size() + 1));
      whitespace();
    } while (take('/'));
    Builder value;
    if (take('(')) value = list(depth + path.size(), true);
    else value = Builder.whole();
    for (int i = path.size() - 1; i >= 0; i--) value = Builder.branch(path.get(i), value);
    return value;
  }

  private Builder linkedin(int depth) {
    String field = segment(depth + 1);
    whitespace();
    Builder value;
    if (take(':')) {
      whitespace();
      if (!take('(')) throw error(EXPECTED_OPEN_PAREN, cursor);
      value = list(depth + 1, true);
    } else value = Builder.whole();
    return Builder.branch(field, value);
  }

  // Null denotes a wildcard; even the empty string is a valid literal (quoted) name.
  private String segment(int depth) {
    whitespace();
    int start = cursor;
    if (depth > options.maxDepth()) {
      throw limit(LimitExceededException.Limit.QUERY_DEPTH, options.maxDepth(), depth, start);
    }
    if (++selectors > options.maxSelectors()) {
      throw limit(LimitExceededException.Limit.SELECTORS, options.maxSelectors(), selectors, start);
    }
    if (dialect == QueryDialect.GOOGLE && take('*')) return null;
    if (dialect == QueryDialect.LINKEDIN && source.startsWith("$*", cursor)) {
      cursor += 2;
      return null;
    }
    if (take('"')) return quoted(start);
    while (!end()) {
      char c = source.charAt(cursor);
      if (Character.isSurrogate(c)) {
        if (!Character.isHighSurrogate(c)
            || cursor + 1 == source.length()
            || !Character.isLowSurrogate(source.charAt(cursor + 1)))
          throw error(INVALID_NAME, cursor);
      }
      int cp = source.codePointAt(cursor);
      if (cp <= 0x20
          || cp == 0x7f
          || Character.isWhitespace(cp)
          || Character.isSpaceChar(cp)
          || ",:/()*$[]=~\"\\".indexOf(cp) >= 0) break;
      cursor += Character.charCount(cp);
    }
    if (cursor == start) throw error(EXPECTED_SELECTOR, cursor);
    return source.substring(start, cursor);
  }

  private String quoted(int opening) {
    StringBuilder decoded = new StringBuilder();
    List<Integer> origins = new ArrayList<>();
    while (!end()) {
      int origin = cursor;
      char c = source.charAt(cursor++);
      if (c == '"') {
        validateSurrogates(decoded, origins);
        return decoded.toString();
      }
      if (c < 0x20) throw error(INVALID_NAME, origin);
      if (c == '\\') {
        if (end()) throw error(INVALID_ESCAPE, origin);
        char escape = source.charAt(cursor++);
        c =
            switch (escape) {
              case '"', '\\', '/' -> escape;
              case 'b' -> '\b';
              case 'f' -> '\f';
              case 'n' -> '\n';
              case 'r' -> '\r';
              case 't' -> '\t';
              case 'u' -> unicodeEscape(origin);
              default -> throw error(INVALID_ESCAPE, origin);
            };
      }
      // Detect a completed invalid surrogate sequence before reading subsequent tokens.
      if (!decoded.isEmpty()) {
        char previous = decoded.charAt(decoded.length() - 1);
        if (Character.isHighSurrogate(previous) && !Character.isLowSurrogate(c)) {
          throw error(INVALID_NAME, origins.get(origins.size() - 1));
        }
        if (Character.isLowSurrogate(c) && !Character.isHighSurrogate(previous)) {
          throw error(INVALID_NAME, origin);
        }
      } else if (Character.isLowSurrogate(c)) throw error(INVALID_NAME, origin);
      decoded.append(c);
      origins.add(origin);
    }
    throw error(UNTERMINATED_NAME, opening);
  }

  private char unicodeEscape(int origin) {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      if (end()) throw error(INVALID_ESCAPE, origin);
      char c = source.charAt(cursor++);
      int digit =
          c >= '0' && c <= '9'
              ? c - '0'
              : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
      if (digit < 0) throw error(INVALID_ESCAPE, origin);
      value = value * 16 + digit;
    }
    return (char) value;
  }

  private void validateSurrogates(StringBuilder value, List<Integer> origins) {
    if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1))) {
      throw error(INVALID_NAME, origins.get(origins.size() - 1));
    }
  }

  private void whitespace() {
    while (!end()) {
      char c = source.charAt(cursor);
      if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
      cursor++;
    }
  }

  private boolean take(char expected) {
    if (!end() && source.charAt(cursor) == expected) {
      cursor++;
      return true;
    }
    return false;
  }

  private boolean end() {
    return cursor == source.length();
  }

  private QuerySyntaxException error(QuerySyntaxException.Code code, int offset) {
    return new QuerySyntaxException(code, source, offset);
  }

  private LimitExceededException limit(
      LimitExceededException.Limit kind, long max, long actual, int offset) {
    return new LimitExceededException(kind, max, actual, SourcePosition.at(source, offset), null);
  }

  private static final class Builder {
    boolean whole;
    final Map<String, Builder> fields = new TreeMap<>();
    Builder wildcard;

    static Builder whole() {
      Builder b = new Builder();
      b.whole = true;
      return b;
    }

    static Builder branch(String field, Builder value) {
      Builder b = new Builder();
      if (field == null) b.wildcard = value;
      else b.fields.put(field, value);
      return b;
    }

    void merge(Builder other) {
      if (whole) return;
      if (other.whole) {
        whole = true;
        fields.clear();
        wildcard = null;
        return;
      }
      other.fields.forEach(
          (name, value) ->
              fields.merge(
                  name,
                  value,
                  (a, b) -> {
                    a.merge(b);
                    return a;
                  }));
      if (wildcard == null) wildcard = other.wildcard;
      else if (other.wildcard != null) wildcard.merge(other.wildcard);
    }

    Selection freeze() {
      if (whole) return Selection.WHOLE;
      Map<String, Selection> frozen = new TreeMap<>();
      fields.forEach((name, value) -> frozen.put(name, value.freeze()));
      return new Selection(false, frozen, wildcard == null ? null : wildcard.freeze());
    }
  }
}
