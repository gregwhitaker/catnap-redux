package com.github.gregwhitaker.catnap.examples.quarkus;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json;
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@QuarkusTest
@TestProfile(LinkedInResponseTest.LinkedIn.class)
class LinkedInResponseTest {
  @TestHTTPResource URI url;

  public static class LinkedIn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "catnap.dialect", "LINKEDIN", "catnap.parameter", "select", "catnap.cache-capacity", "0");
    }
  }

  private final Jackson2Json json = new Jackson2Json(JsonMapper.builder().build());

  private boolean sameJson(String a, String b) {
    try {
      return json.parse(a.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .equals(json.parse(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.io.IOException error) {
      throw new AssertionError(error);
    }
  }

  String base() {
    return url.toString().replaceAll("/$", "");
  }

  @TestFactory
  List<DynamicTest> configuredDialectAndParameter() {
    return cases("select", true).stream()
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.path(), () -> FiniteResponseContract.verify(base(), test, this::sameJson)))
        .toList();
  }

  @Test
  void defaultParameterIsIgnoredAfterRenaming() throws Exception {
    var response = get(base(), "quote?fields=missing");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("ACME"));
    assertEquals(400, get(base(), "quote" + query("select", "payload(amount)")).statusCode());
  }

  @Test
  void finitePublishersUseTheConfiguredDialect() throws Exception {
    var response = get(base(), "publisher" + query("select", "payload:(amount,internal)"));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        "[{\"payload\":{\"amount\":\"EUR:12.50\"}},{\"payload\":{\"amount\":\"EUR:12.50\"}}]",
        response.body());
  }

  @TestFactory
  List<DynamicTest> streamingDialectAndParameter() {
    return StreamingResponseContract.ndjsonCases("select", true).stream()
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.path(),
                    () -> StreamingResponseContract.verifyNdjson(base(), test, this::sameJson)))
        .toList();
  }

  @Test
  void sseUsesTheConfiguredDialect() throws Exception {
    StreamingResponseContract.verifySse(
        base(),
        "select",
        "payload:(amount,internal,ignored)",
        "{\"payload\":{\"amount\":\"EUR:12.50\"}}",
        this::sameJson);
  }
}
