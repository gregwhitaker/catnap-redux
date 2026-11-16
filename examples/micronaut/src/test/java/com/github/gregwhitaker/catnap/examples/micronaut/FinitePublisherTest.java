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
@Property(name = "catnap.max-payload-bytes", value = "4096")
class FinitePublisherTest {
  @Inject EmbeddedServer server;
  @Inject ProjectionController controller;

  String base() {
    return "http://localhost:" + server.getPort();
  }

  @Test
  void projectsTheCompleteArrayIncludingEmptyArrays() throws Exception {
    var response = get(base(), "publisher" + query("fields", "payload(amount,internal)"));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        "[{\"payload\":{\"amount\":\"EUR:12.50\"}},{\"payload\":{\"amount\":\"EUR:12.50\"}}]",
        response.body());
    assertEquals("[]", get(base(), "publisher-empty?fields=id").body());
  }

  @Test
  void totalDocumentLimitCancelsThePublisherWithoutCommittingOriginalBytes() throws Exception {
    var response = get(base(), "publisher-large?fields=id");
    assertEquals(500, response.statusCode(), response.body());
    assertFalse(response.body().contains("xxxxx"));
    assertTrue(controller.publisherCancelled.get());
    assertTrue(
        controller.publisherItems.get() <= 5,
        "Source overproduction: " + controller.publisherItems.get());
  }
}
