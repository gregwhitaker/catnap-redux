package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import io.quarkus.resteasy.reactive.jackson.CustomSerialization;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Returns domain objects through Quarkus's configured native JSON writer. */
@Path("/catnap")
@Produces("application/json")
public class ProjectionResource {
  public interface Public {}

  public interface Internal extends Public {}

  @JsonSerialize(using = MoneySerializer.class)
  public record Money(BigDecimal amount) {}

  public record Quote(
      @JsonView(Public.class) String displayName,
      @JsonView(Public.class) Money amount,
      @JsonView(Internal.class) String internal,
      @JsonIgnore String ignored) {}

  public record Envelope<T>(@JsonView(Public.class) T payload) {}

  public static class MoneySerializer extends StdSerializer<Money> {
    public MoneySerializer() {
      super(Money.class);
    }

    @Override
    public void serialize(Money value, JsonGenerator generator, SerializerProvider context)
        throws IOException {
      generator.writeString(
          context.getAttribute("currency") + ":" + value.amount().toPlainString());
    }
  }

  public static class CurrencyWriter implements BiFunction<ObjectMapper, Type, ObjectWriter> {
    @Override
    public ObjectWriter apply(ObjectMapper mapper, Type type) {
      return mapper.writerFor(mapper.constructType(type)).withAttribute("currency", "EUR");
    }
  }

  private Envelope<Quote> value() {
    return new Envelope<>(
        new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
  }

  @GET
  @Path("/quote")
  @JsonView(Public.class)
  @CustomSerialization(CurrencyWriter.class)
  public Envelope<Quote> quote() {
    return value();
  }

  @GET
  @Path("/quotes")
  @JsonView(Public.class)
  @CustomSerialization(CurrencyWriter.class)
  public List<Envelope<Quote>> quotes() {
    return List.of(value(), value());
  }

  @GET
  @Path("/async")
  @JsonView(Public.class)
  @CustomSerialization(CurrencyWriter.class)
  public Uni<Envelope<Quote>> async() {
    return Uni.createFrom().item(this::value);
  }

  @GET
  @Path("/precise")
  public Map<String, Object> precise() {
    return Map.of("amount", new BigDecimal("0.12345678901234567890123456789"), "unused", true);
  }

  @GET
  @Path("/status")
  public Response status() {
    return Response.status(201)
        .header("X-Application", "preserved")
        .header("ETag", "\"original\"")
        .header("Content-Digest", "stale")
        .header("Accept-Ranges", "bytes")
        .header("Content-Length", 25)
        .entity(Map.of("id", 1, "hidden", "value"))
        .build();
  }

  @GET
  @Path("/error")
  public Response error() {
    return Response.status(422).entity(Map.of("message", "keep")).build();
  }

  @GET
  @Path("/text")
  @Produces("text/plain")
  public String text() {
    return "unchanged";
  }

  @GET
  @Path("/empty")
  public Response empty() {
    return Response.noContent().build();
  }

  @GET
  @Path("/disabled")
  @CatnapProjection(enabled = false)
  public Map<String, Integer> disabled() {
    return Map.of("id", 1, "other", 2);
  }

  @GET
  @Path("/malformed")
  public String malformed() {
    return "{\"secret\":\"never leaked\",";
  }

  @GET
  @Path("/oversized")
  public Map<String, String> oversized() {
    return Map.of("data", "x".repeat(20_000));
  }

  @GET
  @Path("/bytes")
  public byte[] bytes() {
    return "{\"id\":1,\"other\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @GET
  @Path("/writer")
  @WriterProbe.Intercepted
  public WriterProbe.WrittenValue writer() {
    return new WriterProbe.WrittenValue("original");
  }

  @GET
  @Path("/vendor")
  @Produces("application/vnd.catnap+json")
  public Map<String, Integer> vendor() {
    return Map.of("id", 1, "other", 2);
  }
}
