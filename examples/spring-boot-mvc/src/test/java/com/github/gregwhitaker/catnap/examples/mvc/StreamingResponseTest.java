package com.github.gregwhitaker.catnap.examples.mvc;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingLifecycleContract;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"catnap.max-payload-bytes=4096", "catnap.max-query-length=64"})
@Timeout(30)
class StreamingResponseTest {
  @LocalServerPort int port;
  private final Jackson3Json json = new Jackson3Json(JsonMapper.builder().build());

  private String base() {
    return "http://localhost:" + port;
  }

  private boolean equivalent(String a, String b) {
    return json.parse(a).equals(json.parse(b));
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
  void asynchronousControllerDispatchWaitsForEmitterInitialization() throws Exception {
    var response = get(base(), "stream/async?fields=missing");
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("{}\n{}\n", response.body());
  }

  @Test
  void explicitApplicationErrorEventTerminatesSse() throws Exception {
    StreamingLifecycleContract.applicationErrorEvent(base());
  }
}
