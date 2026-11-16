package com.github.gregwhitaker.catnap.jackson3;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.*;
import com.github.gregwhitaker.catnap.core.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.*;
import tools.jackson.databind.ser.std.StdSerializer;

class JacksonBindingTest {
  private static final Jackson3Json JSON = new Jackson3Json(JsonMapper.builder().build());

  @TestFactory
  List<DynamicTest> conformance() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    for (String resource : List.of("projection.json", "streams.json")) {
      try (var input =
          getClass()
              .getResourceAsStream("/com/github/gregwhitaker/catnap/conformance/v1/" + resource)) {
        assertNotNull(input);
        var cases =
            JSON.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)).get("cases");
        for (JsonNode fixture : cases) {
          for (JsonNode variant : fixture.get("queries")) {
            String expression = variant.get("expression").asString();
            var dialect = QueryDialect.valueOf(variant.get("dialect").asString());
            tests.add(
                DynamicTest.dynamicTest(
                    resource
                        + ":"
                        + fixture.get("id").asString()
                        + ":"
                        + dialect
                        + ":"
                        + expression,
                    () -> {
                      var query = Catnap.compile(expression, dialect);
                      JsonNode before = fixture.get("input").deepCopy();
                      if (resource.equals("projection.json")) {
                        assertEquals(
                            fixture.get("expected"),
                            query.evaluate(fixture.get("input"), JSON.adapter()));
                      } else {
                        var actual = JsonNodeFactory.instance.arrayNode();
                        for (JsonNode record : fixture.get("input")) {
                          if (fixture.get("format").asString().equals("NDJSON"))
                            actual.add(query.evaluate(record, JSON.adapter()));
                          else {
                            ObjectNode event = (ObjectNode) record.deepCopy();
                            if (event.get("kind").asString().equals("json"))
                              event.set("data", query.evaluate(event.get("data"), JSON.adapter()));
                            actual.add(event);
                          }
                        }
                        assertEquals(fixture.get("expected"), actual);
                      }
                      assertEquals(before, fixture.get("input"));
                    }));
          }
        }
      }
    }
    return tests;
  }

  public interface Public {}

  public interface Internal extends Public {}

  public record Money(BigDecimal amount) {}

  public record Quote(
      @JsonView(Public.class) String displayName,
      @JsonView(Public.class) Money amount,
      @JsonView(Internal.class) String internal,
      @JsonIgnore String ignored) {}

  public record Envelope<T>(@JsonView(Public.class) T payload) {}

  @Test
  void configuredWriterRetainsViewTypeAttributesNamingAndCustomSerializer() throws Exception {
    var module = new SimpleModule();
    module.addSerializer(
        Money.class,
        new StdSerializer<Money>(Money.class) {
          @Override
          public void serialize(
              Money value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(
                context.getAttribute("currency") + ":" + value.amount().toPlainString());
          }
        });
    var mapper =
        JsonMapper.builder()
            .addModule(module)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
            .build();
    var json = new Jackson3Json(mapper);
    var value =
        new Envelope<>(
            new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "ignored"));
    var writer =
        mapper
            .writerFor(new TypeReference<Envelope<Quote>>() {})
            .withView(Public.class)
            .withAttribute("currency", "EUR");
    assertEquals(
        json.parse("{\"payload\":{\"display_name\":\"ACME\",\"amount\":\"EUR:12.50\"}}"),
        json.toTree(value, writer));
    var query = Catnap.compile("payload(amount,internal,ignored)", QueryDialect.GOOGLE);
    assertEquals(
        json.parse("{\"payload\":{\"amount\":\"EUR:12.50\"}}"), json.project(value, writer, query));
    assertEquals(
        json.parse("{\"payload\":{\"amount\":\"EUR:12.50\",\"internal\":\"internal\"}}"),
        json.project(value, writer.withView(Internal.class), query));
  }

  public interface Base {
    String getName();
  }

  public static class Child implements Base {
    @Override
    public String getName() {
      return "ACME";
    }

    public String getSubtypeOnly() {
      return "not declared";
    }
  }

  @Test
  void declaredGenericTypeReachesNativeWriter() throws Exception {
    var mapper = JsonMapper.builder().enable(MapperFeature.USE_STATIC_TYPING).build();
    var json = new Jackson3Json(mapper);
    var writer = mapper.writerFor(new TypeReference<List<Base>>() {});
    assertEquals(json.parse("[{\"name\":\"ACME\"}]"), json.toTree(List.of(new Child()), writer));
  }

  @Test
  void wholeCopiesContainersAndMutableBinaryLeaves() throws Exception {
    ObjectNode input = JsonNodeFactory.instance.objectNode();
    byte[] data = {1, 2, 3};
    input.set("bytes", JsonNodeFactory.instance.binaryNode(data));
    input.set("nested", JSON.parse("{\"a\":[1]}"));
    ObjectNode result =
        (ObjectNode) Catnap.compile("*", QueryDialect.GOOGLE).evaluate(input, JSON.adapter());
    result.get("bytes").binaryValue()[0] = 9;
    ((ArrayNode) result.get("nested").get("a")).add(2);
    assertEquals(1, input.get("bytes").binaryValue()[0]);
    assertEquals(1, input.get("nested").get("a").size());
    data[1] = 8;
    assertEquals(2, result.get("bytes").binaryValue()[1]);
  }

  @Test
  void preciseConversionAndStrictParsing() throws Exception {
    BigDecimal precise = new BigDecimal("0.123456789012345678901234567890123456789");
    var mapper = JsonMapper.builder().build();
    assertEquals(
        0,
        precise.compareTo(JSON.toTree(precise, mapper.writerFor(BigDecimal.class)).decimalValue()));
    assertEquals(0, precise.compareTo(JSON.parse(precise.toPlainString()).decimalValue()));
    assertTrue(JSON.toTree(null, mapper.writer()).isNull());
    assertThrows(Exception.class, () -> JSON.parse("1 2"));
    assertThrows(Exception.class, () -> JSON.parse(""));
    assertThrows(
        EvaluationException.class,
        () ->
            Catnap.compile("*", QueryDialect.GOOGLE)
                .evaluate(new POJONode(new StringBuilder("mutable")), JSON.adapter()));
  }
}
