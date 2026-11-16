package com.github.gregwhitaker.catnap.examples.mvc;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"catnap.enabled=false", "catnap.max-payload-bytes=1"})
class DisabledProjectionTest {
  @LocalServerPort int port;

  @Test
  void disabledAdapterUsesNativeSerializationEvenWithAnInvalidQuery() throws Exception {
    var response = get("http://localhost:" + port, "quote" + query("fields", "("));
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("ACME"));
    assertTrue(response.body().contains("EUR:12.50"));
    assertFalse(response.body().contains("internal"));
  }
}
