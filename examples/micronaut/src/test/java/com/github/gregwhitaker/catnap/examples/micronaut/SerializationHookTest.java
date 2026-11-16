package com.github.gregwhitaker.catnap.examples.micronaut;

import com.github.gregwhitaker.catnap.testkit.probe.HttpProbe;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class SerializationHookTest {
  @Inject EmbeddedServer server;

  @Test
  void configuredSerializationSurvivesAllResponseModes() throws Exception {
    HttpProbe.verify("http://localhost:" + server.getPort());
  }
}
