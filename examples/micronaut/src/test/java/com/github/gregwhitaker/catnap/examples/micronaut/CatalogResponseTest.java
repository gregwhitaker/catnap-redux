package com.github.gregwhitaker.catnap.examples.micronaut;

import com.github.gregwhitaker.catnap.micronaut.MicronautJson;
import com.github.gregwhitaker.catnap.testkit.probe.CatalogResponseContract;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class CatalogResponseTest {
  @Inject EmbeddedServer server;
  private final MicronautJson json = new MicronautJson(128);

  String base() {
    return "http://localhost:" + server.getPort();
  }

  private boolean sameJson(String a, String b) {

    try {
      return json.parse(a.getBytes(StandardCharsets.UTF_8))
          .equals(json.parse(b.getBytes(StandardCharsets.UTF_8)));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @TestFactory
  List<DynamicTest> catalogOverHttpWithDefaultDialect() {
    return CatalogResponseContract.cases().stream()
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.name(),
                    () -> CatalogResponseContract.verify(base(), test, this::sameJson)))
        .toList();
  }

  @Test
  void filteringDoesNotMutateTheCatalog() throws Exception {
    CatalogResponseContract.verifySourceUnchanged(base(), this::sameJson);
  }
}
