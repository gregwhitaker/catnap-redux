package com.github.gregwhitaker.catnap.benchmarks;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed inputs shared by setup validation and measurements; no input generation is timed. */
public record Workload(Object value, String google, String linkedIn, Object expected) {
  public String query(QueryDialect dialect) {
    return dialect == QueryDialect.LINKEDIN ? linkedIn : google;
  }

  public static Workload named(String name) {
    return switch (name) {
      case "SMALL" ->
          new Workload(
              item(1),
              "id,name,price(amount)",
              "id,name,price:(amount)",
              object("id", 1, "name", "ACME", "price", object("amount", new BigDecimal("12.50"))));
      case "LARGE_ARRAY" -> {
        var input = new ArrayList<Object>();
        var expected = new ArrayList<Object>();
        for (int i = 0; i < 4096; i++) {
          input.add(item(i));
          expected.add(object("id", i, "price", object("amount", new BigDecimal("12.50"))));
        }
        yield new Workload(input, "id,price(amount)", "id,price:(amount)", expected);
      }
      case "DEEP" -> {
        Object input = object("value", "kept", "ignored", "discarded");
        Object expected = object("value", "kept");
        for (int i = 0; i < 48; i++) {
          input = object("child", input, "ignored", "discarded");
          expected = object("child", expected);
        }
        yield new Workload(
            input,
            "child/".repeat(48) + "value",
            "child:(".repeat(48) + "value" + ")".repeat(48),
            expected);
      }
      case "SPARSE" -> {
        var input = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 512; i++) input.put("field" + i, item(i));
        yield new Workload(
            input, "field255(id)", "field255:(id)", object("field255", object("id", 255)));
      }
      case "WILDCARD" -> {
        var input = new LinkedHashMap<String, Object>();
        var expected = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 64; i++) {
          input.put("entry" + i, item(i));
          expected.put("entry" + i, object("id", i));
        }
        yield new Workload(
            object("byId", input), "byId/*/id", "byId:($*:(id))", object("byId", expected));
      }
      default -> throw new IllegalArgumentException(name);
    };
  }

  private static Map<String, Object> item(int id) {
    return object(
        "id",
        id,
        "name",
        "ACME",
        "price",
        object("amount", new BigDecimal("12.50"), "currency", "USD"),
        "padding",
        "x".repeat(64),
        "enabled",
        true);
  }

  private static Map<String, Object> object(Object... pairs) {
    var value = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
    return value;
  }
}
