package com.github.gregwhitaker.catnap.examples.jackson3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit serialization boundary prototype; it is not the production Catnap evaluator. */
public final class JacksonProbe {
  public interface PublicView {}

  public interface InternalView extends PublicView {}

  public record Money(BigDecimal value) {
    @JsonValue
    public String encoded() {
      return "USD:" + value.toPlainString();
    }
  }

  public record Quote(
      @JsonView(PublicView.class) String displayName,
      @JsonView(PublicView.class) Money amount,
      @JsonView(InternalView.class) String internal,
      @JsonIgnore String ignored) {}

  public record Envelope<T>(@JsonView(PublicView.class) T payload) {}

  private final JsonMapper mapper;

  public JacksonProbe(JsonMapper mapper) {
    this.mapper = mapper;
  }

  public static JsonMapper configuredMapper() {
    return JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
        .build();
  }

  public JsonNode project(ProbeRequest request) {
    var declaredType = mapper.constructType(new TypeReference<Envelope<Quote>>() {});
    var writer =
        mapper
            .writerFor(declaredType)
            .withView(request.internalView() ? InternalView.class : PublicView.class);
    var input =
        new Envelope<>(
            new Quote(
                request.requestId() + ":ACME",
                new Money(new BigDecimal("12.50")),
                "internal-only",
                "never-serialize"));
    // The configured writer preserves the declared generic type and active view.
    JsonNode full = mapper.readTree(writer.writeValueAsBytes(input));
    JsonNode payload = full.get("payload");
    Set<String> names = new HashSet<>();
    payload.propertyNames().forEach(names::add);
    request.verifyCompletePayload(names);
    ObjectNode selected = mapper.createObjectNode();
    if (payload.has(request.field())) selected.set(request.field(), payload.get(request.field()));
    ObjectNode result = mapper.createObjectNode();
    result.set("payload", selected);
    return result;
  }
}
