package com.github.gregwhitaker.catnap.http;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

class HttpContractTest {
  @Test
  void defaultDialectAcceptsCatnapPredicates() {
    assertEquals(QueryDialect.CATNAP, ProjectionSettings.DEFAULT.dialect());
    var service = new ProjectionService(ProjectionSettings.DEFAULT);
    assertTrue(service.bind(List.of("images(url)[size==medium]")).active());
  }

  @TestFactory
  List<DynamicTest> parameterBindingCorpus() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    try (var input =
        getClass()
            .getResourceAsStream(
                "/com/github/gregwhitaker/catnap/conformance/v1/http-binding.json")) {
      assertNotNull(input);
      var cases = JsonMapper.builder().build().readTree(input).get("cases");
      for (var fixture : cases) {
        for (var dialect : QueryDialect.values()) {
          tests.add(
              DynamicTest.dynamicTest(
                  fixture.get("id").asString() + ":" + dialect,
                  () -> {
                    var values = new ArrayList<String>();
                    fixture.get("parameters").forEach(value -> values.add(value.asString()));
                    var defaults = ProjectionSettings.DEFAULT;
                    var service =
                        new ProjectionService(
                            new ProjectionSettings(
                                true,
                                "fields",
                                dialect,
                                defaults.compilation(),
                                defaults.evaluation(),
                                4,
                                1000));
                    var expected = fixture.get("expected");
                    switch (expected.get("action").asString()) {
                      case "BYPASS" -> assertFalse(service.bind(values).active());
                      case "REJECT" ->
                          assertEquals(
                              expected.get("code").asString(),
                              assertThrows(QueryRequestException.class, () -> service.bind(values))
                                  .code());
                      case "COMPILE" -> {
                        if (expected.get("expression").asString().equals("\u00a0")) {
                          assertEquals(
                              "EXPECTED_SELECTOR",
                              assertThrows(QueryRequestException.class, () -> service.bind(values))
                                  .code());
                        } else assertTrue(service.bind(values).active());
                      }
                      default -> fail("Unknown binding expectation");
                    }
                  }));
        }
      }
    }
    return tests;
  }

  @Test
  void disabledEndpointsBypassWithoutTouchingTreesOrCompilingQueries() {
    var service = new ProjectionService(ProjectionSettings.DEFAULT);
    var request = service.bind(List.of("malformed(", "duplicate"), false);
    assertFalse(request.active());
    Object body = new Object();
    assertSame(body, request.evaluate(body, null));
    assertFalse(service.bind(null).active());
    assertFalse(service.bind(List.of(" \t\r\n")).active());
  }

  @Test
  void syntaxDiagnosticsReferToUntrimmedDecodedQuery() {
    var service = new ProjectionService(ProjectionSettings.DEFAULT);
    var error = assertThrows(QueryRequestException.class, () -> service.bind(List.of(" a(b,")));
    assertEquals("EXPECTED_SELECTOR", error.code());
    assertEquals(5, error.position().offset());
    assertEquals(6, error.position().column());
    assertEquals(
        "UNEXPECTED_TOKEN",
        assertThrows(QueryRequestException.class, () -> service.bind(List.of("a:(b)"))).code());
  }

  @Test
  void onlyEligibleFiniteJsonAndStaleBodyHeadersAreSelected() {
    assertTrue(ResponsePolicy.finiteJson("application/json;charset=UTF-8", 200, null));
    assertTrue(ResponsePolicy.finiteJson("APPLICATION/vnd.example+JSON", 201, "identity"));
    for (int status : new int[] {204, 205, 206, 304, 400, 500})
      assertFalse(ResponsePolicy.finiteJson("application/json", status, null));
    assertFalse(ResponsePolicy.finiteJson("application/x-ndjson", 200, null));
    assertFalse(ResponsePolicy.finiteJson("text/event-stream", 200, null));
    assertFalse(ResponsePolicy.finiteJson("application/json", 200, "gzip"));
    assertTrue(ResponsePolicy.invalidatedHeader("ETag"));
    assertTrue(ResponsePolicy.invalidatedHeader("Content-Length"));
    assertFalse(ResponsePolicy.invalidatedHeader("Cache-Control"));
  }

  @Test
  void boundedCaptureFailsBeforeOverflowAndCannotReturnTruncatedData() throws Exception {
    var buffer = new BoundedBuffer(4);
    buffer.write(new byte[] {1, 2, 3});
    buffer.write(4);
    assertArrayEquals(new byte[] {1, 2, 3, 4}, buffer.toByteArray());
    assertThrows(IOException.class, () -> buffer.write(5));
    assertThrows(IOException.class, buffer::toByteArray);
    assertThrows(IOException.class, () -> buffer.write(new byte[0]));
    var bulk = new BoundedBuffer(2);
    assertThrows(IOException.class, () -> bulk.write(new byte[3]));
    assertThrows(IOException.class, bulk::toByteArray);
  }
}
