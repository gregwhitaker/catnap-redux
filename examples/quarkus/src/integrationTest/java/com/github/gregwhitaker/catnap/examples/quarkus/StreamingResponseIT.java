package com.github.gregwhitaker.catnap.examples.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.gregwhitaker.catnap.testkit.probe.StreamingLifecycleContract;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Runs the same HTTP contract against the packaged JVM or native executable. */
@QuarkusIntegrationTest
class StreamingResponseIT extends StreamingResponseTest {
  private final HttpClient controls = HttpClient.newHttpClient();
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Override
  StreamingLifecycleContract.Control lifecycleControl() {
    return new StreamingLifecycleContract.Control() {
      public void create(String key) throws Exception {
        call(key, "PUT", "");
      }

      public void release(String key) throws Exception {
        call(key, "POST", "/release");
      }

      public boolean finished(String key) throws Exception {
        return state(key).get("finished").asBoolean();
      }

      public int emitted(String key) throws Exception {
        return state(key).get("emitted").asInt();
      }

      public void remove(String key) throws Exception {
        call(key, "DELETE", "");
      }
    };
  }

  private JsonNode state(String key) throws Exception {
    return mapper.readTree(call(key, "GET", ""));
  }

  private String call(String key, String method, String suffix) throws Exception {
    var response =
        controls.send(
            HttpRequest.newBuilder(url.resolve("/test-control/stream/" + key + suffix))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(method.equals("GET") ? 200 : 204, response.statusCode(), response.body());
    return response.body();
  }
}
