package com.github.gregwhitaker.catnap.examples.quarkus;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json;
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract;
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
@TestProfile(FiniteResponseTest.Limits.class)
class FiniteResponseTest {
  @TestHTTPResource URI url;

  public static class Limits implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("catnap.max-payload-bytes", "4096", "catnap.max-query-length", "64");
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
  List<DynamicTest> finiteResponses() {
    return cases("fields", false).stream()
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.path(), () -> FiniteResponseContract.verify(base(), test, this::sameJson)))
        .toList();
  }

  @Test
  void malformedAndDuplicateQueriesFailBeforeWriting() throws Exception {
    for (String suffix :
        List.of(
            query("fields", "payload("), "?fields=id&fields=id", query("fields", "a".repeat(65)))) {
      var response = get(base(), "quote" + suffix);
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains("Invalid partial response query"));
      assertFalse(response.body().contains("ACME"));
    }
  }

  @Test
  void selectedProviderAndApplicationInterceptorRetainTheirContext() throws Exception {
    for (String suffix : List.of("", "?fields=id")) {
      var response = get(base(), "writer" + suffix);
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(response.body().contains("original-intercepted"), response.body());
      assertEquals(List.of("once"), response.headers().allValues("X-Interceptor"));
      // Without capture, Quarkus encodes headers before MessageBodyWriter.writeTo runs.
      if (!suffix.isEmpty())
        assertEquals("selected", response.headers().firstValue("X-Writer").orElseThrow());
      assertEquals(suffix.isEmpty(), response.body().contains("hidden"));
    }
  }

  @Test
  void nativeByteArrayWriterAndVendorJsonAreProjected() throws Exception {
    for (String path : List.of("bytes", "vendor")) {
      var response = get(base(), path + "?fields=id");
      assertEquals(200, response.statusCode(), response.body());
      assertEquals("{\"id\":1}", response.body());
    }
    assertTrue(
        get(base(), "vendor?fields=id")
            .headers()
            .firstValue("Content-Type")
            .orElseThrow()
            .startsWith("application/vnd.catnap+json"));
  }

  @Test
  void nonJsonAndControlResponsesKeepTheirNativePath() throws Exception {
    var text = get(base(), "text?fields=id");
    assertEquals(200, text.statusCode());
    assertEquals("unchanged", text.body());
    assertEquals(204, get(base(), "empty?fields=id").statusCode());
  }

  @Test
  void malformedJsonAndOversizeResponsesNeverFallBackToOriginalPayload() throws Exception {
    for (String path : List.of("malformed", "oversized")) {
      var response = get(base(), path + "?fields=id");
      assertEquals(500, response.statusCode(), response.body());
      assertFalse(response.body().contains("never leaked"));
      assertFalse(response.body().contains("xxxxx"));
    }
    // A missing query does not impose projection's payload limit or parse JSON again.
    assertEquals(200, get(base(), "oversized").statusCode());
    assertEquals("{\"secret\":\"never leaked\",", get(base(), "malformed").body());
  }
}
