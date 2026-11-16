package com.github.gregwhitaker.catnap.examples.quarkus;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DisabledProjectionTest.Disabled.class)
class DisabledProjectionTest {
  @TestHTTPResource URI url;

  public static class Disabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("catnap.enabled", "false", "catnap.max-payload-bytes", "1");
    }
  }

  @Test
  void disabledAdapterUsesNativeSerializationEvenWithAnInvalidQuery() throws Exception {
    var response = get(url.toString().replaceAll("/$", ""), "quote" + query("fields", "("));
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("ACME"));
    assertTrue(response.body().contains("EUR:12.50"));
    assertFalse(response.body().contains("internal"));
  }

  @Test
  void disabledProjectionStillHasBoundedExplicitStreams() throws Exception {
    String base = url.toString().replaceAll("/$", "");
    assertEquals(200, get(base, "stream/empty" + query("fields", "(")).statusCode());
    var bounded = get(base, "stream/ndjson" + query("fields", "("));
    assertEquals(500, bounded.statusCode(), bounded.body());
    assertFalse(bounded.body().contains("ACME"));
  }
}
