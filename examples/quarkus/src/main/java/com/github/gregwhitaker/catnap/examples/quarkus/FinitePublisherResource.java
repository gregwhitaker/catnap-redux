package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gregwhitaker.catnap.examples.quarkus.ProjectionResource.*;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.quarkus.CatnapQuarkusResponses;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;

@Path("/catnap")
@Produces("application/json")
@ApplicationScoped
public class FinitePublisherResource {
  @Inject CatnapQuarkusResponses responses;
  @Inject ObjectMapper mapper;
  final AtomicBoolean cancelled = new AtomicBoolean();
  final AtomicInteger emitted = new AtomicInteger();
  private volatile CompletableFuture<Void> waitingStarted = new CompletableFuture<>();
  private volatile CompletableFuture<Void> waitingCancelled = new CompletableFuture<>();

  public void resetWaiting() {
    waitingStarted = new CompletableFuture<>();
    waitingCancelled = new CompletableFuture<>();
  }

  public CompletableFuture<Void> waitingStarted() {
    return waitingStarted;
  }

  public CompletableFuture<Void> waitingCancelled() {
    return waitingCancelled;
  }

  public boolean wasCancelled() {
    return cancelled.get();
  }

  public int emittedCount() {
    return emitted.get();
  }

  /** Example-only observation for both in-process and native-process HTTP tests. */
  @GET
  @Path("/publisher-state")
  @CatnapProjection(enabled = false)
  public Map<String, Object> state() {
    return Map.of(
        "cancelled", wasCancelled(),
        "emitted", emittedCount(),
        "waitingStarted", waitingStarted.isDone(),
        "waitingCancelled", waitingCancelled.isDone());
  }

  @POST
  @Path("/publisher-wait-reset")
  @CatnapProjection(enabled = false)
  public void resetWaitingOverHttp() {
    resetWaiting();
  }

  @GET
  @Path("/publisher")
  public Uni<Response> publisher(@Context ServerRequestContext request) {
    var quote =
        new Envelope<>(new Quote("ACME", new Money(new BigDecimal("12.50")), "internal", "never"));
    var writer =
        mapper
            .writerFor(new TypeReference<Envelope<Quote>>() {})
            .withView(Public.class)
            .withAttribute("currency", "EUR");
    return responses
        .jsonArray(
            request,
            Multi.createFrom().items(quote, quote),
            (value, output) -> writer.writeValue(output, value))
        .map(
            response ->
                Response.fromResponse(response)
                    .header("ETag", "\"original\"")
                    .header("X-Application", "preserved")
                    .build());
  }

  @GET
  @Path("/publisher-empty")
  public Uni<Response> empty(@Context ServerRequestContext request) {
    return responses.jsonArray(
        request,
        Multi.createFrom().empty(),
        (value, output) -> {
          throw new AssertionError("No empty-stream values");
        });
  }

  @GET
  @Path("/publisher-large")
  public Uni<Response> large(@Context ServerRequestContext request) {
    cancelled.set(false);
    emitted.set(0);
    var source =
        Multi.createFrom()
            .range(0, 10_000)
            .map(
                index -> {
                  emitted.incrementAndGet();
                  return Map.of("private", "x".repeat(1024));
                })
            .onCancellation()
            .invoke(() -> cancelled.set(true));
    return responses.jsonArray(
        request, source, (value, output) -> mapper.writeValue(output, value));
  }

  @GET
  @Path("/publisher-error")
  public Uni<Response> error(@Context ServerRequestContext request) {
    return responses.jsonArray(
        request,
        Multi.createFrom().failure(new IllegalStateException("never leaked")),
        (value, output) -> mapper.writeValue(output, value));
  }

  @GET
  @Path("/publisher-malformed")
  public Uni<Response> malformed(@Context ServerRequestContext request) {
    return responses.jsonArray(
        request,
        Multi.createFrom().item(1),
        (value, output) ->
            output.write(
                "{\"private\":\"never leaked\","
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @GET
  @Path("/publisher-wait")
  public Uni<Response> waiting(@Context ServerRequestContext request) {
    var started = waitingStarted;
    var cancelled = waitingCancelled;
    Flow.Publisher<Map<String, Integer>> source =
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  private boolean first = true;
                  private volatile boolean stopped;

                  @Override
                  public void request(long n) {
                    if (!stopped && first) {
                      first = false;
                      subscriber.onNext(Map.of("id", 1, "other", 2));
                      started.complete(null);
                    }
                  }

                  @Override
                  public void cancel() {
                    stopped = true;
                    cancelled.complete(null);
                  }
                });
    return responses.jsonArray(
        request, source, (value, output) -> mapper.writeValue(output, value));
  }
}
