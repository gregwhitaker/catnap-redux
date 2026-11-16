package com.github.gregwhitaker.catnap.examples.mvc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Production adapter example: endpoints return ordinary domain objects and native response types.
 */
@RestController
public class ProjectionController {
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
    public void serialize(Money money, JsonGenerator generator, SerializationContext context) {
      generator.writeString(
          context.getAttribute("currency") + ":" + money.amount().toPlainString());
    }
  }

  private Envelope<Quote> value() {
    return new Envelope<>(
        new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
  }

  @JsonView(Public.class)
  @GetMapping("/catnap/quote")
  Envelope<Quote> quote() {
    return value();
  }

  @JsonView(Public.class)
  @GetMapping("/catnap/quotes")
  List<Envelope<Quote>> quotes() {
    return List.of(value(), value());
  }

  @JsonView(Public.class)
  @GetMapping("/catnap/async")
  Callable<Envelope<Quote>> async() {
    return this::value;
  }

  @GetMapping("/catnap/precise")
  Map<String, Object> precise() {
    return Map.of("amount", new BigDecimal("0.12345678901234567890123456789"), "unused", true);
  }

  @GetMapping("/catnap/status")
  ResponseEntity<Map<String, Object>> status() {
    return ResponseEntity.status(201)
        .header("X-Application", "preserved")
        .header("ETag", "\"original\"")
        .header("Content-Digest", "stale")
        .header("Accept-Ranges", "bytes")
        .contentLength(25)
        .body(Map.of("id", 1, "hidden", "value"));
  }

  @GetMapping("/catnap/error")
  ResponseEntity<Map<String, String>> error() {
    return ResponseEntity.unprocessableContent().body(Map.of("message", "keep"));
  }

  @GetMapping(value = "/catnap/text", produces = "text/plain")
  String text() {
    return "unchanged";
  }

  @GetMapping("/catnap/empty")
  ResponseEntity<Void> empty() {
    return ResponseEntity.noContent().build();
  }

  @CatnapProjection(enabled = false)
  @GetMapping("/catnap/disabled")
  Map<String, Integer> disabled() {
    return Map.of("id", 1, "other", 2);
  }

  @GetMapping(value = "/catnap/malformed", produces = "application/json")
  void malformed(HttpServletResponse response) throws IOException {
    response.setContentType("application/json");
    response.getWriter().write("{\"secret\":\"never leaked\",");
  }

  @GetMapping("/catnap/oversized")
  Map<String, String> oversized() {
    return Map.of("data", "x".repeat(20_000));
  }
}
