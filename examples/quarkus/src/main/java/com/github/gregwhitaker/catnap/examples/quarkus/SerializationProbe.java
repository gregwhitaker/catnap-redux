package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.gregwhitaker.catnap.testkit.probe.ProbeRequest;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;

@Singleton
@RegisterForReflection(
    targets = {
      SerializationProbe.Envelope.class,
      SerializationProbe.Quote.class,
      SerializationProbe.Money.class
    })
public class SerializationProbe {
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

  @Inject ObjectMapper mapper;

  public JsonNode project(ProbeRequest request) {
    try {
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
      JsonNode full = mapper.readTree(writer.writeValueAsBytes(input));
      JsonNode payload = full.get("payload");
      var names = new HashSet<String>();
      payload.fieldNames().forEachRemaining(names::add);
      request.verifyCompletePayload(names);
      ObjectNode selected = mapper.createObjectNode();
      if (payload.has(request.field())) selected.set(request.field(), payload.get(request.field()));
      ObjectNode result = mapper.createObjectNode();
      result.set("payload", selected);
      return result;
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
