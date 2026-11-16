package com.github.gregwhitaker.catnap.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable, dialect-independent selection AST. Literal names and wildcard branches are distinct.
 */
public final class Selection {
  static final Selection WHOLE = new Selection(true, Map.of(), null);
  private final boolean whole;
  private final Map<String, Selection> fields;
  private final Selection wildcard;

  Selection(boolean whole, Map<String, Selection> fields, Selection wildcard) {
    this.whole = whole;
    this.fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    this.wildcard = wildcard;
  }

  /** Whether the entire value and all descendants are selected. */
  public boolean wholeValue() {
    return whole;
  }

  /** Literal child selections in deterministic name order. */
  public Map<String, Selection> fields() {
    return fields;
  }

  /** Selection applied to every immediate object member, if present. */
  public Optional<Selection> wildcard() {
    return Optional.ofNullable(wildcard);
  }

  Selection wildcardBranch() {
    return wildcard;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Selection s
        && whole == s.whole
        && fields.equals(s.fields)
        && Objects.equals(wildcard, s.wildcard);
  }

  @Override
  public int hashCode() {
    return Objects.hash(whole, fields, wildcard);
  }
}
