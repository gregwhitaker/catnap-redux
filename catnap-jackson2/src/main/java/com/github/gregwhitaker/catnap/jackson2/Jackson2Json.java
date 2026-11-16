package com.github.gregwhitaker.catnap.jackson2;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.gregwhitaker.catnap.core.CompiledQuery;
import com.github.gregwhitaker.catnap.core.EvaluationOptions;
import java.io.IOException;
import java.util.Objects;

/** Configured mapper/writer integration with native token conversion and precise JSON reading. */
public final class Jackson2Json {
  private final ObjectMapper mapper;
  private final ObjectReader reader;
  private final Jackson2TreeAdapter adapter;

  public Jackson2Json(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
    this.reader =
        mapper
            .reader()
            .with(
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.adapter = new Jackson2TreeAdapter(mapper.getNodeFactory());
  }

  public Jackson2TreeAdapter adapter() {
    return adapter;
  }

  /** Parse one complete JSON value, retaining arbitrary-precision numbers. */
  public JsonNode parse(String json) throws IOException {
    JsonNode node = reader.readTree(json);
    if (node == null || node.isMissingNode())
      throw new IllegalArgumentException("Expected a JSON value");
    return node;
  }

  /** Parse one encoded JSON value, detecting its Unicode encoding without numeric rounding. */
  public JsonNode parse(byte[] json) throws IOException {
    JsonNode node = reader.readTree(json);
    if (node == null || node.isMissingNode())
      throw new IllegalArgumentException("Expected a JSON value");
    return node;
  }

  /** Use the supplied writer's declared type, view, attributes, filters, and custom serializers. */
  public JsonNode toTree(Object value, ObjectWriter writer) throws IOException {
    Objects.requireNonNull(writer);
    try (TokenBuffer buffer = new TokenBuffer(mapper, false)) {
      writer.writeValue(buffer, value);
      try (var parser = buffer.asParser(mapper)) {
        return reader.readTree(parser);
      }
    }
  }

  public JsonNode project(Object value, ObjectWriter writer, CompiledQuery query)
      throws IOException {
    return project(value, writer, query, EvaluationOptions.DEFAULT);
  }

  public JsonNode project(
      Object value, ObjectWriter writer, CompiledQuery query, EvaluationOptions options)
      throws IOException {
    return query.evaluate(toTree(value, writer), adapter, options);
  }

  /** Encode a materialized projected value using the application's mapper. */
  public String write(JsonNode node) throws IOException {
    return mapper.writeValueAsString(node);
  }
}
