package com.github.gregwhitaker.catnap.core;

import java.util.List;
import java.util.Objects;

/** Immutable CATNAP predicate over the immediate fields of an array's object elements. */
public sealed interface QueryPredicate {
  enum Operator {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    IN,
    OUT
  }

  /** A typed comparison; field names are literal, including any dots or slashes. */
  record Comparison(String field, Operator operator, List<JsonScalar> arguments)
      implements QueryPredicate {
    public Comparison {
      Objects.requireNonNull(field);
      Objects.requireNonNull(operator);
      arguments = List.copyOf(arguments);
      if (arguments.isEmpty()
          || (operator != Operator.IN && operator != Operator.OUT && arguments.size() != 1)) {
        throw new IllegalArgumentException("Invalid comparison arity");
      }
    }
  }

  /** An AND (conjunction=true) or OR group, evaluated left to right with short circuiting. */
  record Junction(boolean conjunction, List<QueryPredicate> terms) implements QueryPredicate {
    public Junction {
      terms = List.copyOf(terms);
      if (terms.size() < 2) throw new IllegalArgumentException("Expected at least two terms");
    }
  }
}
