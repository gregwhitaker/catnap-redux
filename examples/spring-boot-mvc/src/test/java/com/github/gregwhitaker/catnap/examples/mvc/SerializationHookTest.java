package com.github.gregwhitaker.catnap.examples.mvc;

import com.github.gregwhitaker.catnap.testkit.probe.HttpProbe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SerializationHookTest {
  @LocalServerPort int port;

  @Test
  void configuredSerializationSurvivesAllResponseModes() throws Exception {
    HttpProbe.verify("http://localhost:" + port);
  }
}
