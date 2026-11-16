package com.github.gregwhitaker.catnap.core;

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
    return walk(input, List.of(selection), 0, "");
  }

  private N walk(N input, List<Selection> active, int depth, String path) {
    tick(path);
    if (depth > options.maxDepth()) {
      throw new LimitExceededException(
          LimitExceededException.Limit.JSON_DEPTH, options.maxDepth(), depth, null, path);
    }
    try {
      switch (Objects.requireNonNull(adapter.kind(input), "node kind")) {
        case SCALAR:
          return adapter.copyScalar(input);
        case ARRAY:
          List<N> values = new ArrayList<>();
          int index = 0;
          for (N value : adapter.elements(input)) {
            values.add(walk(value, active, depth + 1, path + "/" + index++));
          }
          return adapter.array(values);
        case OBJECT:
          var result = new LinkedHashMap<String, N>();
          boolean whole = active.stream().anyMatch(Selection::wholeValue);
          for (var field : adapter.fields(input)) {
            tick(path);
            String name = Objects.requireNonNull(field.getKey(), "field name");
            List<Selection> selected =
                whole ? List.of(Selection.WHOLE) : matching(active, name, path);
            if (!selected.isEmpty()) {
              String child = path + "/" + name.replace("~", "~0").replace("/", "~1");
              result.put(name, walk(field.getValue(), selected, depth + 1, child));
            }
          }
          return adapter.object(result);
        default:
          throw new IllegalStateException("Unknown JSON kind");
      }
    } catch (EvaluationException | LimitExceededException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new EvaluationException(path, e);
    }
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
