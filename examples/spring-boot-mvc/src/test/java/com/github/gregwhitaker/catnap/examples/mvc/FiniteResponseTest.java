package com.github.gregwhitaker.catnap.examples.mvc;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"catnap.max-payload-bytes=4096", "catnap.max-query-length=64"})
class FiniteResponseTest {
  @LocalServerPort int port;
  private final Jackson3Json json = new Jackson3Json(JsonMapper.builder().build());

  String base() {
    return "http://localhost:" + port;
  }

  @TestFactory
  List<DynamicTest> finiteResponses() {
    return cases("fields", false).stream()
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.path(),
                    () ->
                        FiniteResponseContract.verify(
                            base(), test, (a, b) -> json.parse(a).equals(json.parse(b)))))
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
