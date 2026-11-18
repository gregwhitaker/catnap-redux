package com.github.gregwhitaker.catnap.examples.micronaut;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.micronaut.MicronautJson;
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "catnap.max-payload-bytes", value = "4096")
@Property(name = "catnap.max-query-length", value = "64")
class FiniteResponseTest {
  @Inject EmbeddedServer server;
  private final MicronautJson json = new MicronautJson(128);

  private boolean sameJson(String a, String b) {
    try {
      return json.parse(a.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .equals(json.parse(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.io.IOException error) {
      throw new AssertionError(error);
    }
  }

  String base() {
    return "http://localhost:" + server.getPort();
  }

  @TestFactory
  List<DynamicTest> finiteResponses() {
    return catnapCases("fields").stream()
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
