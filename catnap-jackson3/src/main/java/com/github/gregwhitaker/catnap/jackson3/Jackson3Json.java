package com.github.gregwhitaker.catnap.jackson3;

import com.github.gregwhitaker.catnap.core.CompiledQuery;
import com.github.gregwhitaker.catnap.core.EvaluationOptions;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.util.TokenBuffer;

/** Configured mapper/writer integration with native token conversion and precise JSON reading. */
public final class Jackson3Json {
  private final ObjectMapper mapper;
  private final ObjectReader reader;
  private final Jackson3TreeAdapter adapter;

  public Jackson3Json(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
    this.reader =
        mapper
            .reader()
            .with(
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.adapter = new Jackson3TreeAdapter(mapper.getNodeFactory());
  }

  public Jackson3TreeAdapter adapter() {
    return adapter;
  }

  /** Parse one complete JSON value, retaining arbitrary-precision numbers. */
  public JsonNode parse(String json) {
    JsonNode node = reader.readTree(json);
    if (node == null || node.isMissingNode())
      throw new IllegalArgumentException("Expected a JSON value");
    return node;
  }

  /** Parse a complete encoded JSON value, detecting its Unicode encoding. */
  public JsonNode parse(byte[] json) {
    JsonNode node = reader.readTree(json);
    if (node == null || node.isMissingNode())
      throw new IllegalArgumentException("Expected a JSON value");
    return node;
  }

  /** Use the supplied writer's declared type, view, attributes, filters, and custom serializers. */
  public JsonNode toTree(Object value, ObjectWriter writer) {
    Objects.requireNonNull(writer);
    try (TokenBuffer buffer = writer.writeValueIntoBuffer(value);
        var parser = buffer.asParser()) {
      return reader.readTree(parser);
    }
  }

  public JsonNode project(Object value, ObjectWriter writer, CompiledQuery query) {
    return project(value, writer, query, EvaluationOptions.DEFAULT);
  }

  public JsonNode project(
      Object value, ObjectWriter writer, CompiledQuery query, EvaluationOptions options) {
    return query.evaluate(toTree(value, writer), adapter, options);
  }

  /** Encode a materialized projected value using the application's mapper. */
  public String write(JsonNode node) {
    return mapper.writeValueAsString(node);
  }
}
