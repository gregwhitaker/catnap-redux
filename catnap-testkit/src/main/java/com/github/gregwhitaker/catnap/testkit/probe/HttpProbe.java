package com.github.gregwhitaker.catnap.testkit.probe;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Framework-independent black-box checks against real listening HTTP servers. */
public final class HttpProbe {
  private HttpProbe() {}

  public static void verify(String baseUrl) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    checkResponse(
        send(client, baseUrl, "json", "display_name", "finite", "public"),
        "application/json",
        "{\"payload\":{\"display_name\":\"finite:ACME\"}}");
    checkResponse(
        send(client, baseUrl, "json", "internal", "public-view", "public"),
        "application/json",
        "{\"payload\":{}}");
    checkResponse(
        send(client, baseUrl, "json", "internal", "internal-view", "internal"),
        "application/json",
        "{\"payload\":{\"internal\":\"internal-only\"}}");

    HttpResponse<String> ndjson = send(client, baseUrl, "ndjson", "amount", "records", "public");
    checkResponse(ndjson, "application/x-ndjson", "{\"payload\":{\"amount\":\"USD:12.50\"}}");
    require(ndjson.body().endsWith("\n"), "NDJSON must terminate its record");
    require(
        ndjson.body().lines().filter(s -> !s.isBlank()).count() == 1, "Expected one NDJSON record");

    HttpResponse<String> sse = send(client, baseUrl, "sse", "display_name", "events", "public");
    require(sse.statusCode() == 200, "SSE returned " + sse.statusCode() + ": " + sse.body());
    require(
        sse.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"),
        "SSE content type");
    String wire = sse.body().replace("\r\n", "\n");
    require(wire.contains("id:events") || wire.contains("id: events"), "SSE id lost: " + wire);
    require(wire.contains("event:quote") || wire.contains("event: quote"), "SSE event name lost");
    require(wire.contains("retry:1500") || wire.contains("retry: 1500"), "SSE retry lost");
    require(wire.contains("probe-heartbeat"), "SSE comment lost");
    String data =
        wire.lines()
            .filter(s -> s.startsWith("data:"))
            .map(s -> s.substring(5).stripLeading())
            .reduce("", (a, b) -> a + b);
    require(
        compact(data).equals("{\"payload\":{\"display_name\":\"events:ACME\"}}"),
        "SSE projection/framing: " + wire);
    require(wire.endsWith("\n\n"), "SSE event terminator lost");

    var first =
        CompletableFuture.supplyAsync(
            () -> uncheckedSend(client, baseUrl, "display_name", "parallel-a"));
    var second =
        CompletableFuture.supplyAsync(() -> uncheckedSend(client, baseUrl, "amount", "parallel-b"));
    checkResponse(
        first.get(),
        "application/x-ndjson",
        "{\"payload\":{\"display_name\":\"parallel-a:ACME\"}}");
    checkResponse(second.get(), "application/x-ndjson", "{\"payload\":{\"amount\":\"USD:12.50\"}}");
  }

  private static HttpResponse<String> uncheckedSend(
      HttpClient client, String base, String field, String id) {
    try {
      return send(client, base, "ndjson", field, id, "public");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpResponse<String> send(
      HttpClient client, String base, String path, String field, String id, String view)
      throws Exception {
    String fields = URLEncoder.encode("payload(" + field + ")", StandardCharsets.UTF_8);
    URI uri =
        URI.create(
            base + "/probe/" + path + "?fields=" + fields + "&requestId=" + id + "&view=" + view);
    return client.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void checkResponse(
      HttpResponse<String> response, String mediaType, String expected) {
    require(response.statusCode() == 200, "HTTP " + response.statusCode() + ": " + response.body());
    require(
        response.headers().firstValue("content-type").orElse("").startsWith(mediaType),
        "Expected " + mediaType + ", headers were " + response.headers());
    require(
        compact(response.body()).equals(expected),
        "Expected " + expected + " but received " + response.body());
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "");
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
