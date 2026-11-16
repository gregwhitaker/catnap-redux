package com.github.gregwhitaker.catnap.examples.micronaut;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "catnap.enabled", value = "false")
@Property(name = "catnap.max-payload-bytes", value = "1")
class DisabledProjectionTest {
  @Inject EmbeddedServer server;

  @Test
  void disabledAdapterUsesNativeSerializationEvenWithAnInvalidQuery() throws Exception {
    var response = get("http://localhost:" + server.getPort(), "quote" + query("fields", "("));
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("ACME"));
    assertTrue(response.body().contains("EUR:12.50"));
    assertFalse(response.body().contains("internal"));
  }

  @Test
  void explicitStreamsRemainAvailableAndBoundedWhenProjectionIsDisabled() throws Exception {
    String base = "http://localhost:" + server.getPort();
    assertEquals(200, get(base, "stream/empty" + query("fields", "(")).statusCode());
    var bounded = get(base, "stream/ndjson" + query("fields", "("));
    assertEquals(500, bounded.statusCode(), bounded.body());
    assertFalse(bounded.body().contains("ACME"));
  }
}
