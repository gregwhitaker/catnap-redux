package com.github.gregwhitaker.catnap.testkit.probe;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.require;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Real HTTP lifecycle checks against example sources controlled through StreamLifecycleProbe. */
public final class StreamingLifecycleContract {
  private StreamingLifecycleContract() {}

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /** Controls may live in the test JVM or behind an example server's HTTP test endpoints. */
  public interface Control {
    void create(String key) throws Exception;

    void release(String key) throws Exception;

    boolean finished(String key) throws Exception;

    int emitted(String key) throws Exception;

    void remove(String key) throws Exception;

    default void awaitFinished(String key) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!finished(key)) {
        require(System.nanoTime() < deadline, "Source did not finish: " + key);
        Thread.sleep(10);
      }
    }
  }

  private static final Control LOCAL =
      new Control() {
        public void create(String key) {
          StreamLifecycleProbe.CONNECTIONS.put(key, new StreamLifecycleProbe());
        }

        public void release(String key) {
          StreamLifecycleProbe.CONNECTIONS.get(key).release.complete(null);
        }

        public boolean finished(String key) {
          return StreamLifecycleProbe.CONNECTIONS.get(key).finished.isDone();
        }

        public int emitted(String key) {
          return StreamLifecycleProbe.CONNECTIONS.get(key).emitted.get();
        }

        public void remove(String key) {
          var probe = StreamLifecycleProbe.CONNECTIONS.remove(key);
          if (probe != null) probe.release.complete(null);
        }
      };

  public static Control localControl() {
    return LOCAL;
  }

  public static void earlyDeliveryAndConnectionIsolation(String base, String protocol)
      throws Exception {
    earlyDeliveryAndConnectionIsolation(base, protocol, LOCAL);
  }

  public static void earlyDeliveryAndConnectionIsolation(
      String base, String protocol, Control control) throws Exception {
    String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
    control.create(a);
    control.create(b);
    try (var readerA = reader(open(base, protocol + "?key=" + a + "&fields=id"));
        var readerB = reader(open(base, protocol + "?key=" + b + "&fields=other"))) {
      require("{\"id\":0}".equals(payload(readerA, protocol)), "First connection's selection");
      require(
          "{\"other\":\"private\"}".equals(payload(readerB, protocol)),
          "Second connection's selection");
      require(
          !control.finished(a) && !control.finished(b),
          "First items arrive while both sources remain open");
      control.release(a);
      control.release(b);
      require(
          "{\"id\":1}".equals(payload(readerA, protocol)), "First connection's second selection");
      require(
          "{\"other\":\"private\"}".equals(payload(readerB, protocol)),
          "Second connection's second selection");
      require(
          payload(readerA, protocol) == null && payload(readerB, protocol) == null,
          "Normal completion");
      control.awaitFinished(a);
      control.awaitFinished(b);
    } finally {
      control.remove(a);
      control.remove(b);
    }
  }

  public static void errorsBeforeAndAfterCommitment(String base, String protocol) throws Exception {
    errorsBeforeAndAfterCommitment(base, protocol, LOCAL);
  }

  public static void errorsBeforeAndAfterCommitment(String base, String protocol, Control control)
      throws Exception {
    for (String mode : List.of("before", "serialize-before")) {
      var response = open(base, protocol + "?mode=" + mode + "&fields=id");
      try (var body = response.body()) {
        require(
            response.statusCode() == 500, "Uncommitted failure status: " + response.statusCode());
        require(
            !new String(body.readAllBytes(), StandardCharsets.UTF_8).contains("never leaked"),
            "Private error input");
      }
    }
    for (String mode : List.of("after", "serialize", "parse", "limit", "depth")) {
      String key = UUID.randomUUID().toString();
      control.create(key);
      try {
        var response = open(base, protocol + "?key=" + key + "&fields=id&mode=" + mode);
        try (var reader = reader(response)) {
          require(response.statusCode() == 200, "Committed response status");
          require("{\"id\":0}".equals(payload(reader, protocol)), "Valid item before " + mode);
          control.release(key);
          try {
            require(payload(reader, protocol) == null, "No original/error fallback for " + mode);
          } catch (ExecutionException failure) {
            require(
                failure.getCause() instanceof IOException,
                "Expected truncated HTTP transfer: " + failure);
          }
          control.awaitFinished(key);
        }
      } finally {
        control.remove(key);
      }
    }
  }

  public static void applicationErrorEvent(String base) throws Exception {
    applicationErrorEvent(base, LOCAL);
  }

  public static void applicationErrorEvent(String base, Control control) throws Exception {
    for (String mode : List.of("after", "serialize", "parse", "limit")) {
      String key = UUID.randomUUID().toString();
      control.create(key);
      try (var reader =
          reader(open(base, "sse?key=" + key + "&fields=id&recover=true&mode=" + mode))) {
        require("{\"id\":0}".equals(payload(reader, "sse")), "Valid event before failure");
        control.release(key);
        var error = event(reader);
        require(
            error != null
                && "temporary failure".equals(error.data())
                && "application-error".equals(error.event())
                && "failure".equals(error.id()),
            "Explicit protocol error event: " + error);
        require(payload(reader, "sse") == null, "Error event ends the stream");
        control.awaitFinished(key);
      } finally {
        control.remove(key);
      }
    }
  }

  public static void slowConsumerAndDisconnect(String base, String protocol) throws Exception {
    slowConsumerAndDisconnect(base, protocol, LOCAL);
  }

  public static void slowConsumerAndDisconnect(String base, String protocol, Control control)
      throws Exception {
    String key = UUID.randomUUID().toString();
    control.create(key);
    try {
      try (var reader =
          reader(open(base, protocol + "?key=" + key + "&mode=infinite&fields=id,other"))) {
        require(payload(reader, protocol).contains("\"id\":0"), "Early item from infinite source");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int previous = -1, stable = 0;
        while (stable < 4) {
          require(
              System.nanoTime() < deadline, "Source must stop while the consumer stops reading");
          Thread.sleep(100);
          int count = control.emitted(key);
          require(count < 10_000, "Production exceeded the transport bound: " + count);
          stable = count == previous ? stable + 1 : 0;
          previous = count;
        }
        require(!control.finished(key), "Source remains open under backpressure");
      }
      control.awaitFinished(key);
    } finally {
      control.remove(key);
    }
  }

  private static HttpResponse<InputStream> open(String base, String suffix) throws Exception {
    return CLIENT
        .sendAsync(
            HttpRequest.newBuilder(URI.create(base + "/catnap/stream/lifecycle/" + suffix))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream())
        .get(5, TimeUnit.SECONDS);
  }

  private static BufferedReader reader(HttpResponse<InputStream> response) {
    return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
      @Override
      public void close() throws IOException {
        // Unblock a timed-out asynchronous read before acquiring BufferedReader's read lock.
        response.body().close();
        super.close();
      }
    };
  }

  private static String payload(BufferedReader reader, String protocol) throws Exception {
    if (protocol.equals("sse")) {
      var event = event(reader);
      return event == null ? null : event.data();
    }
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return reader.readLine();
              } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
              }
            })
        .get(5, TimeUnit.SECONDS);
  }

  private static StreamingResponseContract.Event event(BufferedReader reader) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                StringBuilder wire = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                  wire.append(line).append('\n');
                  if (line.isEmpty()) break;
                }
                if (wire.isEmpty()) return null;
                return StreamingResponseContract.events(wire.toString()).get(0);
              } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
              }
            })
        .get(5, TimeUnit.SECONDS);
  }
}
