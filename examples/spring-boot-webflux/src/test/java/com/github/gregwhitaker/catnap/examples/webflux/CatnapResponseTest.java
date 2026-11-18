package com.github.gregwhitaker.catnap.examples.webflux;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"catnap.dialect=CATNAP", "catnap.parameter=select", "catnap.cache-capacity=0"})
class CatnapResponseTest {
  @LocalServerPort int port;
  private final Jackson3Json json = new Jackson3Json(JsonMapper.builder().build());

  String base() {
    return "http://localhost:" + port;
  }

  @TestFactory
  List<DynamicTest> configuredDialectAndParameter() {
    return catnapCases("select").stream()
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
  void streamingUsesConfiguredDialectAndParameter() throws Exception {
    for (var test : StreamingResponseContract.ndjsonCases("select", false))
      StreamingResponseContract.verifyNdjson(
          base(), test, (a, b) -> json.parse(a).equals(json.parse(b)));
    StreamingResponseContract.verifySse(
        base(),
        "select",
        "payload(amount,internal,ignored)",
        "{\"payload\":{\"amount\":\"EUR:12.50\"}}",
        (a, b) -> json.parse(a).equals(json.parse(b)));
  }

  @Test
  void defaultParameterIsIgnoredAfterRenaming() throws Exception {
    var response = get(base(), "quote?fields=missing");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("ACME"));
    assertEquals(400, get(base(), "quote" + query("select", "payload:(amount)")).statusCode());
  }

  @Test
  void malformedPredicatesFailBeforeWriting() throws Exception {
    var response = get(base(), "catalog" + query("select", "payload(amount)[display_name=ACME]"));
    assertEquals(400, response.statusCode(), response.body());
    assertFalse(response.body().contains("EUR:12.50"));
  }
}
