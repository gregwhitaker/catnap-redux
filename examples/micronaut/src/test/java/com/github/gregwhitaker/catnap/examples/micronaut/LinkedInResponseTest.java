package com.github.gregwhitaker.catnap.examples.micronaut;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.micronaut.MicronautJson;
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract;
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
@Property(name = "catnap.dialect", value = "LINKEDIN")
@Property(name = "catnap.parameter", value = "select")
@Property(name = "catnap.cache-capacity", value = "0")
class LinkedInResponseTest {
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
