package com.github.gregwhitaker.catnap.examples.quarkus;

import com.github.gregwhitaker.catnap.testkit.probe.HttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SerializationHookTest {
  @TestHTTPResource URI base;

  @Test
  void configuredSerializationSurvivesAllResponseModes() throws Exception {
    HttpProbe.verify(base.toString().replaceAll("/$", ""));
  }
}
