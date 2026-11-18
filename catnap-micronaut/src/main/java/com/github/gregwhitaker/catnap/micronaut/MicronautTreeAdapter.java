package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.core.JsonScalar;
import com.github.gregwhitaker.catnap.core.JsonTreeAdapter;
import io.micronaut.json.tree.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projection directly over Micronaut's native JSON tree, with fresh result containers. */
public final class MicronautTreeAdapter implements JsonTreeAdapter<JsonNode> {
  public static final MicronautTreeAdapter INSTANCE = new MicronautTreeAdapter();

  private MicronautTreeAdapter() {}

  @Override
  public Kind kind(JsonNode node) {
    Objects.requireNonNull(node, "Use JsonNode.nullNode() for JSON null");
    return node.isObject() ? Kind.OBJECT : node.isArray() ? Kind.ARRAY : Kind.SCALAR;
  }

  @Override
  public Iterable<? extends Map.Entry<String, JsonNode>> fields(JsonNode object) {
    return object.entries();
  }

  @Override
  public Iterable<JsonNode> elements(JsonNode array) {
    return array.values();
  }

  @Override
  public JsonNode object(Map<String, JsonNode> fields) {
    return JsonNode.createObjectNode(new LinkedHashMap<>(fields));
  }

  @Override
  public JsonNode array(List<JsonNode> elements) {
    return JsonNode.createArrayNode(new ArrayList<>(elements));
  }

  @Override
  public JsonScalar scalarValue(JsonNode node) {
    if (node.isNull()) return JsonScalar.NULL;
    if (node.isNumber()) return new JsonScalar(new BigDecimal(node.getNumberValue().toString()));
    if (node.isBoolean()) return new JsonScalar(node.getBooleanValue());
    if (node.isString()) return new JsonScalar(node.getStringValue());
    throw new IllegalArgumentException("Expected a materialized JSON scalar");
  }

  @Override
  public JsonNode copyScalar(JsonNode scalar) {
    if (scalar.isNumber()) {
      Number value = scalar.getNumberValue();
      if ((value instanceof Float f && !Float.isFinite(f))
          || (value instanceof Double d && !Double.isFinite(d)))
        throw new IllegalArgumentException("JSON numbers must be finite");
      if (!(value instanceof Integer
          || value instanceof Long
          || value instanceof Short
          || value instanceof Byte
          || value instanceof Float
          || value instanceof Double
          || value instanceof BigDecimal
          || value instanceof BigInteger))
        return JsonNode.createNumberNode(new BigDecimal(value.toString()));
    }
    return scalar;
  }
}
