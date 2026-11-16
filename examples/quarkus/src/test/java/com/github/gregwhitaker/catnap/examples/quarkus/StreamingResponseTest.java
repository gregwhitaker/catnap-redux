package com.github.gregwhitaker.catnap.examples.quarkus;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingLifecycleContract;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

@QuarkusTest
@TestProfile(FiniteResponseTest.Limits.class)
@Timeout(30)
class StreamingResponseTest {
  @TestHTTPResource URI url;
  private final Jackson2Json json = new Jackson2Json(JsonMapper.builder().build());

  private String base() {
    return url.toString().replaceAll("/$", "");
  }

  StreamingLifecycleContract.Control lifecycleControl() {
    return StreamingLifecycleContract.localControl();
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
    var records = response.body().lines().toList();
    assertEquals(2, records.size());
    assertTrue(equivalent("{\"label\":\"é😀\"}", records.get(0)), records.get(0));
    assertTrue(equivalent("{\"label\":\"second\"}", records.get(1)), records.get(1));
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
                                base(), protocol, lifecycleControl())),
                    DynamicTest.dynamicTest(
                        protocol + " failures",
                        () ->
                            StreamingLifecycleContract.errorsBeforeAndAfterCommitment(
                                base(), protocol, lifecycleControl())),
                    DynamicTest.dynamicTest(
                        protocol + " slow consumer and disconnect",
                        () ->
                            StreamingLifecycleContract.slowConsumerAndDisconnect(
                                base(), protocol, lifecycleControl())))
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
    StreamingLifecycleContract.applicationErrorEvent(base(), lifecycleControl());
    for (String mode : List.of("before", "serialize-before")) {
      var response = get(base(), "stream/lifecycle/sse?recover=true&mode=" + mode + "&fields=id");
      assertEquals(500, response.statusCode(), response.body());
      assertFalse(response.body().contains("application-error"));
    }
  }

  @Test
  void nativeEndpointStatusAndHeaderCustomizersAreRetained() throws Exception {
    var response = get(base(), "stream/status?fields=id");
    assertEquals(201, response.statusCode(), response.body());
    assertEquals("preserved", response.headers().firstValue("X-Native").orElseThrow());
    assertTrue(response.headers().firstValue("ETag").isEmpty());
    assertEquals("stale", get(base(), "stream/status").headers().firstValue("ETag").orElseThrow());
  }

  @Test
  void sseLineEndingsPreserveOneEnvelopeAndCannotInjectMetadataFields() throws Exception {
    var response = get(base(), "stream/sse-lines?fields=missing");
    assertEquals(200, response.statusCode(), response.body());
    var events = StreamingResponseContract.events(response.body());
    assertEquals(1, events.size(), response.body());
    var event = events.get(0);
    assertEquals("id", event.id());
    assertEquals("name", event.event());
    assertEquals("one\ntwo\nlast\n", event.data());
    assertEquals(List.of("first", "second", "third", ""), event.comments());
  }
}
