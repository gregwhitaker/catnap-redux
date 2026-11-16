package com.github.gregwhitaker.catnap.testkit.probe;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The same finite HTTP assertions run against every adapter's example using its native serializer.
 */
public final class FiniteResponseContract {
  private FiniteResponseContract() {}

  public record Case(String path, int status, String json) {}

  public static List<Case> cases(String parameter, boolean linkedIn) {
    String nested =
        linkedIn ? "payload:(amount,internal,ignored)" : "payload(amount,internal,ignored)";
    String projected = "{\"payload\":{\"amount\":\"EUR:12.50\"}}";
    String full = "{\"payload\":{\"display_name\":\"ACME\",\"amount\":\"EUR:12.50\"}}";
    var result = new ArrayList<Case>();
    result.add(new Case("quote", 200, full));
    result.add(new Case("quote" + query(parameter, " \t"), 200, full));
    result.add(new Case("quote" + query(parameter, nested), 200, projected));
    result.add(new Case("quote" + query(parameter, "missing"), 200, "{}"));
    result.add(
        new Case(
            "quotes" + query(parameter, nested), 200, "[" + projected + "," + projected + "]"));
    result.add(new Case("async" + query(parameter, nested), 200, projected));
    result.add(
        new Case(
            "precise" + query(parameter, "amount"),
            200,
            "{\"amount\":0.12345678901234567890123456789}"));
    result.add(new Case("status" + query(parameter, "id"), 201, "{\"id\":1}"));
    result.add(new Case("error" + query(parameter, "missing"), 422, "{\"message\":\"keep\"}"));
    result.add(new Case("disabled" + query(parameter, "("), 200, "{\"id\":1,\"other\":2}"));
    return List.copyOf(result);
  }

  public static String query(String parameter, String expression) {
    return "?" + parameter + "=" + URLEncoder.encode(expression, StandardCharsets.UTF_8);
  }

  public static HttpResponse<String> get(String base, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base + "/catnap/" + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  public static void verify(String base, Case test, BiPredicate<String, String> equivalent)
      throws Exception {
    var response = get(base, test.path());
    require(
        response.statusCode() == test.status(),
        test.path() + " status: " + response.statusCode() + " body: " + response.body());
    require(
        equivalent.test(test.json(), response.body()), test.path() + " body: " + response.body());
    require(
        response.headers().firstValue("content-type").orElse("").startsWith("application/json"),
        "JSON content type");
    if (test.path().startsWith("status")) {
      require(
          response.headers().firstValue("x-application").orElse("").equals("preserved"),
          "Application header");
      for (String header : List.of("etag", "content-digest", "accept-ranges"))
        require(response.headers().firstValue(header).isEmpty(), "Stale " + header);
      response
          .headers()
          .firstValueAsLong("content-length")
          .ifPresent(
              length ->
                  require(
                      length == response.body().getBytes(StandardCharsets.UTF_8).length,
                      "Projected content length"));
    }
  }

  public static void require(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
}
