package com.github.gregwhitaker.catnap.examples.micronaut;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller("/catnap")
public class ProjectionController {
  final AtomicBoolean publisherCancelled = new AtomicBoolean();
  final AtomicInteger publisherItems = new AtomicInteger();

  public interface Public {}

  public interface Internal extends Public {}

  @Serdeable.Serializable(using = MoneySerializer.class)
  public record Money(BigDecimal amount) {}

  @Serdeable
  public record Quote(
      @JsonView(Public.class) String displayName,
      @JsonView(Public.class) Money amount,
      @JsonView(Internal.class) String internal,
      @JsonIgnore String ignored) {}

  @Serdeable
  public record Envelope<T>(@JsonView(Public.class) T payload) {}

  @Singleton
  public static class MoneySerializer implements Serializer<Money> {
    private final String currency;

    public MoneySerializer(@Value("${example.currency:EUR}") String currency) {
      this.currency = currency;
    }

    @Override
    public void serialize(
        Encoder encoder, EncoderContext context, Argument<? extends Money> type, Money value)
        throws IOException {
      encoder.encodeString(currency + ":" + value.amount().toPlainString());
    }
  }

  private Envelope<Quote> value() {
    return new Envelope<>(
        new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
  }

  @JsonView(Public.class)
  @Get("/quote")
  Envelope<Quote> quote() {
    return value();
  }

  @JsonView(Internal.class)
  @Get("/quote-internal")
  Envelope<Quote> internalQuote() {
    return value();
  }

  @JsonView(Public.class)
  @Get("/quotes")
  List<Envelope<Quote>> quotes() {
    return List.of(value(), value());
  }

  @JsonView(Public.class)
  @Get("/async")
  Mono<Envelope<Quote>> async() {
    return Mono.fromSupplier(this::value);
  }

  @JsonView(Public.class)
  @Get("/publisher")
  Flux<Envelope<Quote>> publisher() {
    return Flux.just(value(), value());
  }

  @Get("/publisher-empty")
  Flux<Map<String, Integer>> emptyPublisher() {
    return Flux.empty();
  }

  @Get("/publisher-large")
  Flux<Map<String, String>> largePublisher() {
    publisherCancelled.set(false);
    publisherItems.set(0);
    return Flux.range(0, 10_000)
        .map(
            i -> {
              publisherItems.incrementAndGet();
              return Map.of("secret", "x".repeat(1024));
            })
        .doOnCancel(() -> publisherCancelled.set(true));
  }

  @Get("/precise")
  Map<String, Object> precise() {
    return Map.of("amount", new BigDecimal("0.12345678901234567890123456789"), "unused", true);
  }

  @Get("/status")
  HttpResponse<Map<String, Object>> status() {
    return HttpResponse.created(Map.<String, Object>of("id", 1, "hidden", "value"))
        .header("X-Application", "preserved")
        .header("ETag", "\"original\"")
        .header("Content-Digest", "stale")
        .header("Accept-Ranges", "bytes")
        .header("Content-Length", "25");
  }

  @Get("/error")
  HttpResponse<Map<String, String>> error() {
    return HttpResponse.<Map<String, String>>status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(Map.of("message", "keep"));
  }

  @Get(value = "/text", produces = "text/plain")
  String text() {
    return "unchanged";
  }

  @Get("/empty")
  HttpResponse<Void> empty() {
    return HttpResponse.noContent();
  }

  @CatnapProjection(enabled = false)
  @Get("/disabled")
  Map<String, Integer> disabled() {
    return Map.of("id", 1, "other", 2);
  }

  @Get(value = "/malformed", produces = "application/json")
  String malformed() {
    return "{\"secret\":\"never leaked\",";
  }

  @Get("/oversized")
  Map<String, String> oversized() {
    return Map.of("data", "x".repeat(20_000));
  }
}
