package com.github.gregwhitaker.catnap.examples.mvc;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.spring.mvc.MvcStreamOptions;
import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StreamingTimeoutTest.Limits.class)
@Timeout(15)
class StreamingTimeoutTest {
  @LocalServerPort int port;

  @TestConfiguration(proxyBeanMethods = false)
  static class Limits {
    @Bean
    MvcStreamOptions streamLimits() {
      return new MvcStreamOptions(1, 1, 4, 400);
    }
  }

  @Test
  void nativeEmitterTimeoutCancelsAWaitingPublisher() throws Exception {
    String key = UUID.randomUUID().toString();
    var probe = new StreamLifecycleProbe();
    StreamLifecycleProbe.CONNECTIONS.put(key, probe);
    try {
      var response =
          HttpClient.newHttpClient()
              .sendAsync(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://localhost:"
                                  + port
                                  + "/catnap/stream/lifecycle/ndjson?fields=id&key="
                                  + key))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofInputStream())
              .get(5, TimeUnit.SECONDS);
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
        assertEquals(200, response.statusCode());
        assertEquals("{\"id\":0}", reader.readLine());
        probe.finished.get(5, TimeUnit.SECONDS);
        assertEquals(1, probe.emitted.get());
      }
    } finally {
      probe.release.complete(null);
      StreamLifecycleProbe.CONNECTIONS.remove(key);
    }
  }
}
