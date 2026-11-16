package com.github.gregwhitaker.catnap.examples.micronaut;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;

@Singleton
public class SerializationProbe {
  public interface PublicView {}

  public interface InternalView extends PublicView {}

  @Serdeable
  public record Money(BigDecimal value) {
    @JsonValue
    public String encoded() {
      return "USD:" + value.toPlainString();
    }
  }

  @Serdeable
  public record Quote(
      @JsonProperty("display_name") @JsonView(PublicView.class) String displayName,
      @JsonView(PublicView.class) Money amount,
      @JsonView(InternalView.class) String internal,
      @JsonIgnore String ignored) {}

  @Serdeable
  public record Envelope<T>(@JsonView(PublicView.class) T payload) {}

  private final JsonMapper mapper;

  public SerializationProbe(JsonMapper mapper) {
    this.mapper = mapper;
  }

  public JsonNode project(ProbeRequest request) throws IOException {
    var configured =
        mapper.cloneWithViewClass(request.internalView() ? InternalView.class : PublicView.class);
    Argument<Envelope<Quote>> type = (Argument) Argument.of(Envelope.class, Quote.class);
    var input =
        new Envelope<>(
            new Quote(
                request.requestId() + ":ACME",
                new Money(new BigDecimal("12.50")),
                "internal-only",
                "never-serialize"));
    JsonNode full = configured.writeValueToTree(type, input);
    JsonNode payload = full.get("payload");
    var names = new HashSet<String>();
    payload.entries().forEach(entry -> names.add(entry.getKey()));
    request.verifyCompletePayload(names);
    JsonNode value = payload.get(request.field());
    return JsonNode.createObjectNode(
        Map.of(
            "payload",
            JsonNode.createObjectNode(value == null ? Map.of() : Map.of(request.field(), value))));
  }
}
