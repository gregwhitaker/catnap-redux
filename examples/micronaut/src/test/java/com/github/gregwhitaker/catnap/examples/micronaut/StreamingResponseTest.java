package com.github.gregwhitaker.catnap.examples.micronaut;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.micronaut.MicronautJson;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingLifecycleContract;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "catnap.max-payload-bytes", value = "4096")
@Property(name = "catnap.max-query-length", value = "64")
@Timeout(30)
class StreamingResponseTest {
  @Inject EmbeddedServer server;
  private final MicronautJson json = new MicronautJson(128);

  private String base() {
    return "http://localhost:" + server.getPort();
  }

  private boolean equivalent(String a, String b) {
    try {
      return json.parse(a.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .equals(json.parse(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @TestFactory
  List<DynamicTest> ndjsonContract() {
    return StreamingResponseContract.ndjsonCases("fields", false).stream()
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.path(),
                    () -> StreamingResponseContract.verifyNdjson(base(), test, this::equivalent)))
        .toList();
  }

  @Test
  void sseMetadataControlsAndExplicitText() throws Exception {
    StreamingResponseContract.verifySse(
        base(),
        "fields",
        null,
        "{\"payload\":{\"display_name\":\"ACME\",\"amount\":\"EUR:12.50\"}}",
        this::equivalent);
    StreamingResponseContract.verifySse(
        base(),
        "fields",
        "payload(amount,internal,ignored)",
        "{\"payload\":{\"amount\":\"EUR:12.50\"}}",
        this::equivalent);
    StreamingResponseContract.verifySse(base(), "fields", "missing", "{}", this::equivalent);
  }

  @Test
  void splitEncodedRecordsRetainUtf8AndBoundaries() throws Exception {
    var response = get(base(), "stream/encoded?fields=label");
    assertEquals(200, response.statusCode());
    assertEquals(
        List.of("{\"label\":\"é😀\"}", "{\"label\":\"second\"}"), response.body().lines().toList());
  }

  @Test
  void invalidQueriesFailBeforeCommitment() throws Exception {
    for (String path : List.of("ndjson", "sse", "encoded")) {
      for (String query : List.of(query("fields", "("), "?fields=id&fields=id")) {
        var response = get(base(), "stream/" + path + query);
        assertEquals(400, response.statusCode());
        assertFalse(response.body().contains("ACME"));
      }
    }
  }

  @Test
  void invalidEncodedPayloadsNeverEmitOriginalDataWithOrWithoutAQuery() throws Exception {
    for (String fields : List.of("", "&fields=label")) {
      for (String mode :
          List.of("blank", "bare-cr", "tail", "malformed", "utf8", "oversized", "depth")) {
        var response = get(base(), "stream/encoded?mode=" + mode + fields);
        assertEquals(500, response.statusCode(), mode + ": " + response.body());
        assertFalse(response.body().contains("never leaked"));
      }
      for (String mode : List.of("malformed", "oversized", "metadata")) {
        var response = get(base(), "stream/invalid-event?mode=" + mode + fields);
        assertEquals(500, response.statusCode(), mode + ": " + response.body());
        assertFalse(response.body().contains("never leaked"));
      }
    }
  }

  @Test
  void responseMetadataTracksTheProjectedRepresentation() throws Exception {
    assertEquals(
        "\"original\"", get(base(), "stream/ndjson").headers().firstValue("ETag").orElseThrow());
    var selected = get(base(), "stream/ndjson?fields=missing");
    assertTrue(selected.headers().firstValue("ETag").isEmpty());
    assertEquals("preserved", selected.headers().firstValue("X-Application").orElseThrow());
  }

  @TestFactory
  List<DynamicTest> lifecycleContract() {
    return List.of("ndjson", "sse").stream()
        .flatMap(
            protocol ->
                List.of(
                    DynamicTest.dynamicTest(
                        protocol + " early output and isolation",
                        () ->
                            StreamingLifecycleContract.earlyDeliveryAndConnectionIsolation(
                                base(), protocol)),
                    DynamicTest.dynamicTest(
                        protocol + " failures",
                        () ->
                            StreamingLifecycleContract.errorsBeforeAndAfterCommitment(
                                base(), protocol)),
                    DynamicTest.dynamicTest(
                        protocol + " slow consumer and disconnect",
                        () ->
                            StreamingLifecycleContract.slowConsumerAndDisconnect(base(), protocol)))
                    .stream())
        .toList();
  }

  @Test
  void controllerOptOutIsRetained() throws Exception {
    var disabled = get(base(), "stream/disabled" + query("fields", "("));
    assertEquals(200, disabled.statusCode(), disabled.body());
    assertTrue(disabled.body().contains("ACME"));
    assertTrue(disabled.body().contains("EUR:12.50"));
    assertEquals("\"original\"", disabled.headers().firstValue("ETag").orElseThrow());
  }

  @Test
  void explicitApplicationErrorEventTerminatesSse() throws Exception {
    StreamingLifecycleContract.applicationErrorEvent(base());
    for (String mode : List.of("before", "serialize-before")) {
      var response = get(base(), "stream/lifecycle/sse?recover=true&mode=" + mode + "&fields=id");
      assertEquals(500, response.statusCode(), response.body());
      assertFalse(response.body().contains("application-error"));
    }
  }
}
