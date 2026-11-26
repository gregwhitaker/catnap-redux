package com.github.gregwhitaker.catnap.spring.webflux;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

class WebFluxNdjsonDrainTest {
  @Test
  @Timeout(30)
  void aSlowReaderGetsEveryNumberedRecordBeforeTheNextBurstOrConnectionClose() throws Exception {
    int burstSize = 512;
    String padding = "x".repeat(2_048);
    var resume = new CompletableFuture<Void>();
    var firstBacklog = new CompletableFuture<Void>();
    var secondBacklog = new CompletableFuture<Void>();
    var source =
        Flux.range(0, burstSize)
            .concatWith(Mono.fromFuture(resume).thenMany(Flux.range(burstSize, burstSize)))
            .concatWith(Flux.never())
            .doOnNext(
                id -> {
                  if (id == 32) firstBacklog.complete(null);
                  if (id == burstSize + 32) secondBacklog.complete(null);
                });
    var streams = new CatnapWebFluxStreams(new ProjectionService(ProjectionSettings.DEFAULT));
    WebHandler handler =
        exchange ->
            streams.writeNdjson(
                exchange,
                source,
                streams.encoder(
                    exchange,
                    (Integer id, java.io.OutputStream out) ->
                        out.write(
                            ("{\"id\":" + id + ",\"pad\":\"" + padding + "\"}")
                                .getBytes(StandardCharsets.UTF_8))));
    var server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.webHandler(handler).build()))
            .bindNow();
    try {
      var connection =
          (HttpURLConnection)
              URI.create("http://127.0.0.1:" + server.port() + "/").toURL().openConnection();
      connection.setConnectTimeout(5_000);
      connection.setReadTimeout(5_000);
      try (var reader =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        assertEquals(200, connection.getResponseCode());
        for (int id = 0; id < 2 * burstSize; id++) {
          if (id == 0) firstBacklog.get(5, TimeUnit.SECONDS);
          if (id == burstSize) {
            resume.complete(null);
            secondBacklog.get(5, TimeUnit.SECONDS);
          }
          assertEquals("{\"id\":" + id + ",\"pad\":\"" + padding + "\"}", reader.readLine());
        }
      } finally {
        connection.disconnect();
      }
    } finally {
      server.disposeNow();
    }
  }
}
