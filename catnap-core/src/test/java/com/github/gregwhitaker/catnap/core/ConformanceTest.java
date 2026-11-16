package com.github.gregwhitaker.catnap.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConformanceTest {
  static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .build();
  private static final String ROOT = "/com/github/gregwhitaker/catnap/conformance/v1/";

  @TestFactory
  List<DynamicTest> projections() throws IOException {
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode fixture : cases("projection.json")) {
      for (JsonNode query : fixture.path("queries")) {
        tests.add(
            DynamicTest.dynamicTest(
                label(fixture, query),
                () -> {
                  Object input = plain(fixture.path("input"));
                  Object expected = plain(fixture.path("expected"));
                  CompiledQuery plan = compile(query);
                  assertEquals(expected, plan.evaluate(input, PlainTreeAdapter.INSTANCE));
                  assertEquals(plain(fixture.path("input")), input, "Source must not be mutated");
                  assertEquals(
                      expected,
                      plan.evaluate(input, PlainTreeAdapter.INSTANCE),
                      "Plan is reusable");
                }));
      }
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> syntaxDiagnostics() throws IOException {
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode fixture : cases("syntax-errors.json")) {
      tests.add(
          DynamicTest.dynamicTest(
              fixture.path("id").asString(),
              () -> {
                QuerySyntaxException error =
                    assertThrows(QuerySyntaxException.class, () -> compile(fixture.path("query")));
                JsonNode expected = fixture.path("error");
                assertEquals(expected.path("code").asString(), error.code().name());
                assertEquals(expected.path("offset").intValue(), error.position().offset());
                assertEquals(expected.path("line").intValue(), error.position().line());
                assertEquals(expected.path("column").intValue(), error.position().column());
              }));
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> logicalStreamPayloads() throws IOException {
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode fixture : cases("streams.json")) {
      for (JsonNode query : fixture.path("queries")) {
        tests.add(
            DynamicTest.dynamicTest(
                label(fixture, query),
                () -> {
                  CompiledQuery plan = compile(query); // Once per logical stream, not per payload.
                  var output = new ArrayList<>();
                  for (JsonNode record : fixture.path("input")) {
                    if (fixture.path("format").asString().equals("NDJSON")) {
                      output.add(plan.evaluate(plain(record), PlainTreeAdapter.INSTANCE));
                    } else {
                      @SuppressWarnings("unchecked")
                      var envelope = (Map<String, Object>) plain(record);
                      if (record.path("kind").asString().equals("json")) {
                        envelope.put(
                            "data", plan.evaluate(envelope.get("data"), PlainTreeAdapter.INSTANCE));
                      }
                      output.add(envelope);
                    }
                  }
                  assertEquals(plain(fixture.path("expected")), output);
                }));
      }
    }
    return tests;
  }

  static Object plain(JsonNode node) {
    return JSON.readValue(node.toString(), Object.class);
  }

  private static CompiledQuery compile(JsonNode query) {
    return Catnap.compile(
        query.path("expression").asString(),
        QueryDialect.valueOf(query.path("dialect").asString()));
  }

  private static String label(JsonNode fixture, JsonNode query) {
    return fixture.path("id").asString()
        + ":"
        + query.path("dialect").asString()
        + ":"
        + query.path("expression").asString();
  }

  private static JsonNode cases(String file) throws IOException {
    try (var input = ConformanceTest.class.getResourceAsStream(ROOT + file)) {
      assertNotNull(input);
      return JSON.readTree(input).path("cases");
    }
  }
}
