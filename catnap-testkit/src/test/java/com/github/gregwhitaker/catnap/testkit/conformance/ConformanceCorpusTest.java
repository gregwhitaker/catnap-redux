package com.github.gregwhitaker.catnap.testkit.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the specification corpus, independently of the future compiler and evaluator. */
class ConformanceCorpusTest {
  private static final String ROOT = "/com/github/gregwhitaker/catnap/conformance/v1/";
  private static final Set<String> DIALECTS = Set.of("GOOGLE", "LINKEDIN");
  private static final Set<String> ERRORS =
      Set.of(
          "EMPTY_QUERY",
          "EXPECTED_SELECTOR",
          "EXPECTED_OPEN_PAREN",
          "EXPECTED_CLOSE_PAREN",
          "UNEXPECTED_TOKEN",
          "UNTERMINATED_NAME",
          "INVALID_ESCAPE",
          "INVALID_NAME");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  @TestFactory
  List<DynamicTest> corpusIntegrity() throws IOException {
    var tests = new ArrayList<DynamicTest>();
    var resources = new HashSet<String>();
    for (JsonNode entry : read("manifest.json")) {
      fields(entry, "resource");
      String resource = string(entry, "resource");
      assertTrue(resources.add(resource), "Duplicate manifest entry");
      var ids = new HashSet<String>();
      for (JsonNode fixture : read(resource)) {
        String id = string(fixture, "id");
        assertFalse(id.isBlank());
        assertTrue(ids.add(id), "Duplicate ID: " + resource + ":" + id);
        tests.add(
            DynamicTest.dynamicTest(
                resource + ":" + id,
                () -> {
                  switch (resource) {
                    case "projection.json" -> projection(fixture);
                    case "syntax-errors.json" -> diagnostic(fixture);
                    case "http-binding.json" -> http(fixture);
                    case "streams.json" -> stream(fixture);
                    default -> fail("Unknown resource: " + resource);
                  }
                }));
      }
    }
    assertEquals(
        Set.of("projection.json", "syntax-errors.json", "http-binding.json", "streams.json"),
        resources);
    return tests;
  }

  @Test
  void corpusCoversEveryDiagnosticAndKeepsHighPrecisionNumbers() throws IOException {
    var codes = new HashSet<String>();
    var dialects = new HashSet<String>();
    for (JsonNode fixture : read("syntax-errors.json")) {
      codes.add(fixture.path("error").path("code").asText());
      dialects.add(fixture.path("query").path("dialect").asText());
    }
    assertEquals(ERRORS, codes);
    assertEquals(DIALECTS, dialects);
    JsonNode precise = null;
    for (JsonNode fixture : read("projection.json")) {
      if (fixture.path("id").asText().equals("precision")) precise = fixture.path("expected");
    }
    assertNotNull(precise, "The precision fixture is required");
    assertEquals(
        "123456789012345678901234567890", precise.path("large").bigIntegerValue().toString());
    assertEquals(
        0,
        new BigDecimal("0.123456789012345678901234567890123456789")
            .compareTo(precise.path("decimal").decimalValue()));
  }

  private static void projection(JsonNode fixture) {
    fields(fixture, "id", "reason", "queries", "input", "expected");
    assertFalse(string(fixture, "reason").isBlank());
    variants(fixture.path("queries"));
    preservesValuesAndShape(fixture.path("input"), fixture.path("expected"));
  }

  private static void diagnostic(JsonNode fixture) {
    fields(fixture, "id", "query", "input", "error");
    query(fixture.path("query"));
    JsonNode error = fixture.path("error");
    fields(error, "code", "offset", "line", "column", "at");
    assertTrue(ERRORS.contains(string(error, "code")));
    String source = fixture.path("query").path("expression").asText();
    int offset = integer(error, "offset");
    assertTrue(offset >= 0 && offset <= source.length());
    int line = 1;
    int column = 1;
    for (int i = 0; i < offset; i++) {
      char c = source.charAt(i);
      if (c == '\r') {
        line++;
        column = 1;
      } else if (c == '\n') {
        if (i == 0 || source.charAt(i - 1) != '\r') line++;
        column = 1;
      } else {
        column++;
      }
    }
    assertEquals(line, integer(error, "line"));
    assertEquals(column, integer(error, "column"));
    String at =
        offset == source.length()
            ? "<EOF>"
            : new String(Character.toChars(source.codePointAt(offset)));
    assertEquals(at, string(error, "at"));
    if (string(error, "code").equals("EMPTY_QUERY")) {
      assertTrue(source.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'));
      assertEquals(source.length(), offset);
    }
  }

  private static void http(JsonNode fixture) {
    fields(fixture, "id", "parameters", "expected");
    JsonNode parameters = fixture.path("parameters");
    assertTrue(parameters.isArray());
    parameters.forEach(value -> assertTrue(value.isString()));
    JsonNode expected = fixture.path("expected");
    switch (string(expected, "action")) {
      case "BYPASS" -> {
        fields(expected, "action");
        assertTrue(parameters.size() <= 1);
        if (!parameters.isEmpty()) {
          assertTrue(
              parameters
                  .get(0)
                  .asText()
                  .chars()
                  .allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'));
        }
      }
      case "COMPILE" -> {
        fields(expected, "action", "expression");
        assertEquals(1, parameters.size());
        assertEquals(parameters.get(0).asText(), string(expected, "expression"));
      }
      case "REJECT" -> {
        fields(expected, "action", "code");
        assertTrue(parameters.size() > 1);
        assertEquals("MULTIPLE_QUERY_PARAMETERS", string(expected, "code"));
      }
      default -> fail("Unknown HTTP outcome");
    }
  }

  private static void stream(JsonNode fixture) {
    fields(fixture, "id", "reason", "format", "queries", "input", "expected");
    assertFalse(string(fixture, "reason").isBlank());
    variants(fixture.path("queries"));
    JsonNode input = fixture.path("input");
    JsonNode expected = fixture.path("expected");
    assertTrue(input.isArray() && expected.isArray());
    assertEquals(input.size(), expected.size(), "Projection cannot remove logical records/events");
    String format = string(fixture, "format");
    assertTrue(Set.of("NDJSON", "SSE").contains(format));
    for (int i = 0; i < input.size(); i++) {
      if (format.equals("NDJSON")) {
        preservesValuesAndShape(input.get(i), expected.get(i));
      } else {
        JsonNode before = input.get(i);
        JsonNode after = expected.get(i);
        event(before);
        event(after);
        assertEquals(names(before), names(after), "Envelope members must survive");
        before
            .propertyNames()
            .forEach(
                name -> {
                  if (!name.equals("data"))
                    assertEquals(before.get(name), after.get(name), "SSE " + name);
                });
        if (before.path("kind").asText().equals("json")) {
          preservesValuesAndShape(before.get("data"), after.get("data"));
        } else {
          assertEquals(before, after, "Text and control frames must pass unchanged");
        }
      }
    }
  }

  private static void event(JsonNode value) {
    assertTrue(value.isObject());
    assertTrue(
        Set.of("kind", "data", "id", "event", "retry", "comments").containsAll(names(value)));
    String kind = string(value, "kind");
    assertTrue(Set.of("json", "text", "control").contains(kind));
    assertEquals(!kind.equals("control"), value.has("data"));
    if (kind.equals("text")) string(value, "data");
    for (String field : List.of("id", "event")) if (value.has(field)) string(value, field);
    if (value.has("retry")) {
      assertTrue(value.get("retry").isIntegralNumber());
      assertTrue(value.get("retry").bigIntegerValue().signum() >= 0);
    }
    if (value.has("comments")) {
      assertTrue(value.get("comments").isArray());
      value.get("comments").forEach(comment -> assertTrue(comment.isString()));
    }
  }

  // Structural invariant only: this does not interpret a query or derive an expected projection.
  private static void preservesValuesAndShape(JsonNode input, JsonNode output) {
    assertNotNull(input);
    assertNotNull(output);
    if (input.isObject()) {
      assertTrue(output.isObject());
      output
          .propertyNames()
          .forEach(
              name -> {
                assertTrue(input.has(name), "Projection cannot invent field " + name);
                preservesValuesAndShape(input.get(name), output.get(name));
              });
    } else if (input.isArray()) {
      assertTrue(output.isArray());
      assertEquals(input.size(), output.size(), "Projection cannot drop array elements");
      for (int i = 0; i < input.size(); i++) preservesValuesAndShape(input.get(i), output.get(i));
    } else {
      assertEquals(input, output, "Projection cannot rewrite a scalar or null");
    }
  }

  private static void variants(JsonNode variants) {
    assertTrue(variants.isArray());
    var dialects = new HashSet<String>();
    var unique = new HashSet<JsonNode>();
    variants.forEach(
        value -> {
          query(value);
          assertFalse(string(value, "expression").isBlank());
          assertTrue(unique.add(value), "Duplicate query variant");
          dialects.add(value.path("dialect").asText());
        });
    assertEquals(DIALECTS, dialects, "Successful cases require both dialects");
  }

  private static void query(JsonNode value) {
    fields(value, "dialect", "expression");
    assertTrue(DIALECTS.contains(string(value, "dialect")));
    string(value, "expression");
  }

  private static JsonNode read(String resource) throws IOException {
    try (var input = ConformanceCorpusTest.class.getResourceAsStream(ROOT + resource)) {
      assertNotNull(input, "Missing corpus resource " + resource);
      JsonNode root = JSON.readTree(input);
      fields(root, "schemaVersion", "cases");
      assertEquals(1, integer(root, "schemaVersion"));
      assertTrue(root.path("cases").isArray());
      assertFalse(root.path("cases").isEmpty());
      return root.path("cases");
    }
  }

  private static Set<String> names(JsonNode value) {
    var result = new HashSet<String>();
    value.propertyNames().forEach(result::add);
    return result;
  }

  private static void fields(JsonNode value, String... fields) {
    assertTrue(value.isObject());
    assertEquals(Set.of(fields), names(value), "Unexpected or missing fixture fields");
  }

  private static String string(JsonNode value, String field) {
    assertTrue(value.path(field).isString(), "Expected string: " + field);
    return value.get(field).asText();
  }

  private static int integer(JsonNode value, String field) {
    assertTrue(value.path(field).isIntegralNumber(), "Expected integer: " + field);
    return value.get(field).bigIntegerValue().intValueExact();
  }
}
