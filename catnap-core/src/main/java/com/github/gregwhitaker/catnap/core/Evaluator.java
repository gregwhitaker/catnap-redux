package com.github.gregwhitaker.catnap.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Evaluation state is local to one invocation and never attached to a cached query. */
final class Evaluator<N> {
  private final JsonTreeAdapter<N> adapter;
  private final EvaluationOptions options;
  private long work;

  Evaluator(JsonTreeAdapter<N> adapter, EvaluationOptions options) {
    this.adapter = adapter;
    this.options = options;
  }

  N evaluate(N input, Selection selection) {
    return walk(input, List.of(selection), 0, "").value();
  }

  private record Result<N>(boolean included, N value) {}

  private void visit(int depth, String path) {
    tick(path);
    if (depth > options.maxDepth()) {
      throw new LimitExceededException(
          LimitExceededException.Limit.JSON_DEPTH, options.maxDepth(), depth, null, path);
    }
  }

  private Result<N> walk(N input, List<Selection> active, int depth, String path) {
    visit(depth, path);
    try {
      JsonTreeAdapter.Kind kind = Objects.requireNonNull(adapter.kind(input), "node kind");
      if (kind != JsonTreeAdapter.Kind.ARRAY) {
        active = unconditional(active);
        if (active.isEmpty()) return new Result<>(false, null);
      }
      switch (kind) {
        case SCALAR:
          return new Result<>(true, adapter.copyScalar(input));
        case ARRAY:
          List<N> values = new ArrayList<>();
          int index = 0;
          List<Selection.Filtered> filters = new ArrayList<>();
          for (Selection selection : active) filters.addAll(selection.filters());
          List<Selection> base = filters.isEmpty() ? active : unconditional(active);
          // An unconditional whole value dominates every conditional projection.
          if (base.stream().anyMatch(Selection::wholeValue)) filters.clear();
          for (N value : adapter.elements(input)) {
            String child = path + "/" + index++;
            List<Selection> selected = base;
            if (!filters.isEmpty()) {
              visit(depth + 1, child); // Rejected elements still consume depth/work budget.
              selected = new ArrayList<>(base);
              for (Selection.Filtered filter : filters) {
                if (matches(filter.predicate(), value, depth + 1, child)) {
                  selected.add(filter.selection());
                }
              }
            }
            if (!selected.isEmpty()) {
              Result<N> projected = walk(value, selected, depth + 1, child);
              if (projected.included()) values.add(projected.value());
            }
          }
          return new Result<>(true, adapter.array(values));
        case OBJECT:
          var result = new LinkedHashMap<String, N>();
          boolean whole = active.stream().anyMatch(Selection::wholeValue);
          for (var field : adapter.fields(input)) {
            tick(path);
            String name = Objects.requireNonNull(field.getKey(), "field name");
            List<Selection> selected =
                whole ? List.of(Selection.WHOLE) : matching(active, name, path);
            if (!selected.isEmpty()) {
              String child = childPath(path, name);
              Result<N> projected = walk(field.getValue(), selected, depth + 1, child);
              if (projected.included()) result.put(name, projected.value());
            }
          }
          return new Result<>(true, adapter.object(result));
        default:
          throw new IllegalStateException("Unknown JSON kind");
      }
    } catch (EvaluationException | LimitExceededException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new EvaluationException(path, e);
    }
  }

  private List<Selection> unconditional(List<Selection> active) {
    if (active.stream().allMatch(s -> s.filters().isEmpty())) return active;
    List<Selection> result = new ArrayList<>();
    for (Selection selection : active) {
      if (selection.hasUnconditionalSelection()) result.add(selection.withoutFilters());
    }
    return result;
  }

  private boolean matches(QueryPredicate predicate, N input, int depth, String path) {
    tick(path);
    try {
      if (Objects.requireNonNull(adapter.kind(input), "node kind") != JsonTreeAdapter.Kind.OBJECT)
        return false;
      if (predicate instanceof QueryPredicate.Junction junction) {
        for (QueryPredicate term : junction.terms()) {
          boolean match = matches(term, input, depth, path);
          if (match != junction.conjunction()) return match;
        }
        return junction.conjunction();
      }
      QueryPredicate.Comparison comparison = (QueryPredicate.Comparison) predicate;
      for (var field : adapter.fields(input)) {
        tick(path);
        if (comparison.field().equals(Objects.requireNonNull(field.getKey(), "field name"))) {
          return compareField(
              comparison, field.getValue(), depth + 1, childPath(path, field.getKey()));
        }
      }
      return false; // Missing differs from JSON null, even for != and =out=.
    } catch (EvaluationException | LimitExceededException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new EvaluationException(path, e);
    }
  }

  private boolean compareField(
      QueryPredicate.Comparison comparison, N input, int depth, String path) {
    visit(depth, path);
    try {
      if (Objects.requireNonNull(adapter.kind(input), "node kind") != JsonTreeAdapter.Kind.SCALAR)
        return false;
      Object value = Objects.requireNonNull(adapter.scalarValue(input), "scalar value").value();
      QueryPredicate.Operator operator = comparison.operator();
      if (operator == QueryPredicate.Operator.EQ
          || operator == QueryPredicate.Operator.NE
          || operator == QueryPredicate.Operator.IN
          || operator == QueryPredicate.Operator.OUT) {
        boolean equal = false;
        for (JsonScalar argument : comparison.arguments()) {
          tick(path);
          Object expected = argument.value();
          equal =
              value instanceof BigDecimal number && expected instanceof BigDecimal other
                  ? number.compareTo(other) == 0
                  : Objects.equals(value, expected);
          if (equal) break;
        }
        return operator == QueryPredicate.Operator.NE || operator == QueryPredicate.Operator.OUT
            ? !equal
            : equal;
      }
      tick(path);
      Object expected = comparison.arguments().get(0).value();
      if (!(value instanceof BigDecimal number) || !(expected instanceof BigDecimal other))
        return false;
      int ordering = number.compareTo(other);
      return switch (operator) {
        case LT -> ordering < 0;
        case LE -> ordering <= 0;
        case GT -> ordering > 0;
        case GE -> ordering >= 0;
        default -> throw new IllegalStateException("Unknown comparison");
      };
    } catch (EvaluationException | LimitExceededException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new EvaluationException(path, e);
    }
  }

  private static String childPath(String path, String name) {
    return path + "/" + name.replace("~", "~0").replace("/", "~1");
  }

  // Retain matching branches rather than expanding wildcard/literal unions into exponential trees.
  private List<Selection> matching(List<Selection> active, String name, String path) {
    List<Selection> result = new ArrayList<>();
    for (Selection selection : active) {
      tick(path);
      Selection literal = selection.fields().get(name);
      Selection wildcard = selection.wildcardBranch();
      if ((literal != null && literal.wholeValue())
          || (wildcard != null && wildcard.wholeValue())) {
        return List.of(Selection.WHOLE);
      }
      if (literal != null) result.add(literal);
      if (wildcard != null) result.add(wildcard);
    }
    return result;
  }

  private void tick(String path) {
    // Avoid overflow even with a caller-specified Long.MAX_VALUE budget.
    if (work == options.maxWork()) {
      throw new LimitExceededException(
          LimitExceededException.Limit.EVALUATION_WORK,
          options.maxWork(),
          work == Long.MAX_VALUE ? work : work + 1,
          null,
          path);
    }
    work++;
  }
}
