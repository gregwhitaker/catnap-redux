package com.github.gregwhitaker.catnap.examples.quarkus;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@QuarkusTest
@TestProfile(FiniteResponseTest.Limits.class)
@Timeout(30)
class FinitePublisherTest {
  @TestHTTPResource URI url;
  private final JsonMapper mapper = JsonMapper.builder().build();

  String base() {
    return url.toString().replaceAll("/$", "");
  }

  @Test
  void serializesConfiguredGenericValuesThenProjectsOneCompleteArray() throws Exception {
    var response = get(base(), "publisher" + query("fields", "payload(amount,internal,ignored)"));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        "[{\"payload\":{\"amount\":\"EUR:12.50\"}},{\"payload\":{\"amount\":\"EUR:12.50\"}}]",
        response.body());
    assertTrue(response.headers().firstValue("ETag").isEmpty());
    assertEquals("preserved", response.headers().firstValue("X-Application").orElseThrow());
    assertEquals("[]", get(base(), "publisher-empty?fields=id").body());
    assertEquals("[{},{}]", get(base(), "publisher?fields=missing").body());
    var nativeResponse = get(base(), "publisher");
    assertEquals(200, nativeResponse.statusCode(), nativeResponse.body());
    assertTrue(nativeResponse.body().contains("ACME"));
    assertFalse(nativeResponse.body().contains("internal"));
    assertEquals("\"original\"", nativeResponse.headers().firstValue("ETag").orElseThrow());
  }

  @Test
  void aTotalDocumentLimitCancelsBeforeTheOriginalBytesAreCommitted() throws Exception {
    var response = get(base(), "publisher-large?fields=id");
    assertEquals(500, response.statusCode(), response.body());
    assertFalse(response.body().contains("xxxxx"));
    var state = state();
    assertTrue(state.get("cancelled").asBoolean());
    assertTrue(state.get("emitted").asInt() <= 5, "Overproduction: " + state);
  }

  @Test
  void invalidQueriesAndServerFailuresUseTheCorrectStatus() throws Exception {
    assertEquals(400, get(base(), "publisher" + query("fields", "(")).statusCode());
    for (String path : List.of("publisher-error", "publisher-malformed")) {
      var response = get(base(), path + "?fields=id");
      assertEquals(500, response.statusCode(), response.body());
      assertFalse(response.body().contains("never leaked"));
    }
  }

  @Test
  void aDisconnectedClientCancelsAnIncompleteFiniteDocument() throws Exception {
    var reset =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(url.resolve("/catnap/publisher-wait-reset"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
    assertEquals(204, reset.statusCode());
    var response =
        java.net.http.HttpClient.newHttpClient()
            .sendAsync(
                java.net.http.HttpRequest.newBuilder(
                        URI.create(base() + "/catnap/publisher-wait?fields=id"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    try {
      awaitState("waitingStarted");
      assertFalse(response.isDone(), "An incomplete finite array must not be committed");
      response.cancel(true);
      awaitState("waitingCancelled");
    } finally {
      response.cancel(true);
    }
  }

  private JsonNode state() throws Exception {
    var response = get(base(), "publisher-state");
    assertEquals(200, response.statusCode(), response.body());
    return mapper.readTree(response.body());
  }

  private void awaitState(String field) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (!state().get(field).asBoolean()) {
      assertTrue(System.nanoTime() < deadline, "Source state did not reach " + field);
      Thread.sleep(10);
    }
  }
}
