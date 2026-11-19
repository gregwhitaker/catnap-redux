package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json;
import com.github.gregwhitaker.catnap.testkit.probe.CatalogResponseContract;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@QuarkusTest
class CatalogResponseTest {
  @TestHTTPResource URI url;
  private final Jackson2Json json = new Jackson2Json(JsonMapper.builder().build());

  String base() {
    return url.toString().replaceAll("/$", "");
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
