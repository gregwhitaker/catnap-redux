package com.github.gregwhitaker.catnap.jackson3;

import com.github.gregwhitaker.catnap.core.JsonScalar;
import com.github.gregwhitaker.catnap.core.JsonTreeAdapter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Native Jackson 3 tree binding. No container or mutable binary leaf aliases the input. */
public final class Jackson3TreeAdapter implements JsonTreeAdapter<JsonNode> {
  public static final Jackson3TreeAdapter INSTANCE =
      new Jackson3TreeAdapter(JsonNodeFactory.instance);
  private final JsonNodeFactory factory;

  public Jackson3TreeAdapter(JsonNodeFactory factory) {
    this.factory = Objects.requireNonNull(factory);
  }

  @Override
  public Kind kind(JsonNode node) {
    Objects.requireNonNull(node, "Use Jackson NullNode for JSON null");
    if (node.isMissingNode() || node.isPojo())
      throw new IllegalArgumentException(
          "Missing/POJO nodes are not materialized JSON; serialize the value first");
    return node.isObject() ? Kind.OBJECT : node.isArray() ? Kind.ARRAY : Kind.SCALAR;
  }

  @Override
  public Iterable<? extends Map.Entry<String, JsonNode>> fields(JsonNode node) {
    return node.properties();
  }

  @Override
  public Iterable<JsonNode> elements(JsonNode node) {
    return node;
  }

  @Override
  public JsonNode object(Map<String, JsonNode> fields) {
    return factory.objectNode().setAll(fields);
  }

  @Override
  public JsonNode array(List<JsonNode> elements) {
    return factory.arrayNode(elements.size()).addAll(elements);
  }

  @Override
  public JsonScalar scalarValue(JsonNode node) {
    if (node.isNull()) return JsonScalar.NULL;
    if (node.isNumber()) return new JsonScalar(node.decimalValue());
    if (node.isBoolean()) return new JsonScalar(node.booleanValue());
    if (node.isString() || node.isBinary()) return new JsonScalar(node.asText());
    throw new IllegalArgumentException("Expected a materialized JSON scalar");
  }

  @Override
  public JsonNode copyScalar(JsonNode node) {
    if (node.isBinary()) {
      try {
        return factory.binaryNode(node.binaryValue().clone());
      } catch (Exception error) {
        throw new IllegalArgumentException("Invalid binary node", error);
      }
    }
    return node.deepCopy();
  }
}
