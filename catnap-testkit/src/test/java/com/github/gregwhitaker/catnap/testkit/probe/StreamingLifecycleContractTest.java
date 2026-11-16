package com.github.gregwhitaker.catnap.testkit.probe;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class StreamingLifecycleContractTest {
  @Test
  void aStalledBodyFailsWithinTheReadDeadlineAndClosesItsReaders() throws Exception {
    var release = new CountDownLatch(1);
    var workers =
        Executors.newCachedThreadPool(
            task -> {
              var thread = new Thread(task, "stalled-lifecycle-server");
              thread.setDaemon(true);
              return thread;
            });
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(workers);
    server.createContext(
        "/catnap/stream/lifecycle/ndjson",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
          exchange.sendResponseHeaders(200, 0);
          try (var output = exchange.getResponseBody()) {
            String first =
                exchange.getRequestURI().getQuery().contains("fields=other")
                    ? "{\"other\":\"private\"}\n"
                    : "{\"id\":0}\n";
            output.write(first.getBytes(StandardCharsets.UTF_8));
            output.flush();
            // Deliberately ignore source release: the next asynchronous read must time out.
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () ->
              assertThrows(
                  TimeoutException.class,
                  () ->
                      StreamingLifecycleContract.earlyDeliveryAndConnectionIsolation(
                          base, "ndjson")));
    } finally {
      release.countDown();
      server.stop(0);
      workers.shutdownNow();
    }
  }
}
