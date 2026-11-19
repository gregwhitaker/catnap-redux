package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import com.github.gregwhitaker.catnap.testkit.probe.CatalogResponseContract;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogResponseTest {
  @LocalServerPort int port;
  private final Jackson3Json json = new Jackson3Json(JsonMapper.builder().build());

  String base() {
    return "http://localhost:" + port;
  }

  private boolean sameJson(String a, String b) {
    return json.parse(a).equals(json.parse(b));
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
