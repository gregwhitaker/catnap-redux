package com.github.gregwhitaker.catnap.micronaut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import io.micronaut.json.tree.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class MicronautTreeTest {
  private final MicronautJson json = new MicronautJson(128);

  @TestFactory
  List<DynamicTest> conformance() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    for (String resource : List.of("projection.json", "streams.json")) {
      try (var input =
          getClass()
              .getResourceAsStream("/com/github/gregwhitaker/catnap/conformance/v1/" + resource)) {
        assertNotNull(input);
        for (JsonNode fixture : json.parse(input.readAllBytes()).get("cases").values()) {
          for (JsonNode variant : fixture.get("queries").values()) {
            String expression = variant.get("expression").getStringValue();
            var dialect = QueryDialect.valueOf(variant.get("dialect").getStringValue());
            tests.add(
                DynamicTest.dynamicTest(
                    resource + ":" + fixture.get("id").getStringValue() + ":" + expression,
                    () -> {
                      var query = Catnap.compile(expression, dialect);
                      byte[] before =
                          json.write(fixture.get("input"), StandardCharsets.UTF_8, 1_048_576);
                      JsonNode result;
                      if (resource.equals("projection.json"))
                        result =
                            query.evaluate(fixture.get("input"), MicronautTreeAdapter.INSTANCE);
                      else {
                        var records = new ArrayList<JsonNode>();
                        for (JsonNode item : fixture.get("input").values()) {
                          if (fixture.get("format").getStringValue().equals("NDJSON"))
                            records.add(query.evaluate(item, MicronautTreeAdapter.INSTANCE));
                          else {
                            Map<String, JsonNode> event = new LinkedHashMap<>();
                            item.entries()
                                .forEach(entry -> event.put(entry.getKey(), entry.getValue()));
                            if (item.get("kind").getStringValue().equals("json"))
                              event.put(
                                  "data",
                                  query.evaluate(item.get("data"), MicronautTreeAdapter.INSTANCE));
                            records.add(JsonNode.createObjectNode(event));
                          }
                        }
                        result = JsonNode.createArrayNode(records);
                      }
                      assertEquals(fixture.get("expected"), result);
                      assertArrayEquals(
                          before,
                          json.write(fixture.get("input"), StandardCharsets.UTF_8, 1_048_576));
                    }));
          }
        }
      }
    }
    return tests;
  }

  @Test
  void resultOwnershipIsIndependentEvenWithMutableBackingCollectionsAndNumbers() {
    var backing = new LinkedHashMap<String, JsonNode>();
    AtomicInteger number = new AtomicInteger(1);
    backing.put("id", JsonNode.from(number));
    JsonNode source = JsonNode.createObjectNode(backing);
    JsonNode result =
        Catnap.compile("*", QueryDialect.GOOGLE).evaluate(source, MicronautTreeAdapter.INSTANCE);
    backing.put("another", JsonNode.createStringNode("new"));
    number.set(9);
    assertNull(result.get("another"));
    assertEquals(1, result.get("id").getIntValue());
  }

  @Test
  void strictParsingPreservesPrecisionAndBoundsNesting() throws Exception {
    String precise =
        "{\"n\":0.12345678901234567890123456789,\"large\":12345678901234567890123456789}";
    assertEquals(
        precise,
        new String(
            json.write(
                json.parse(precise.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8, 4096),
            StandardCharsets.UTF_8));
    for (String malformed : List.of("", "{} {}", "{\"a\":", "[1,]"))
      assertThrows(Exception.class, () -> json.parse(malformed.getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        Exception.class,
        () -> new MicronautJson(2).parse("[[[[1]]]]".getBytes(StandardCharsets.UTF_8)));
  }
}
