package com.github.gregwhitaker.catnap.spring.mvc;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CatnapMvcStreamsTest {
  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest();
    request.setAttribute(CatnapMvcStreamFilter.INSTALLED, true);
    request.addParameter("fields", "id");
    return request;
  }

  @Test
  void missingStartupFilterFailsBeforeAcceptingAnEmitter() {
    try (var streams = new CatnapMvcStreams(new ProjectionService(ProjectionSettings.DEFAULT))) {
      var request = new MockHttpServletRequest();
      assertThrows(
          IllegalStateException.class,
          () ->
              streams.ndjson(
                  request,
                  new MockHttpServletResponse(),
                  streams.encoder(request, (value, out) -> {})));
    }
  }

  @Test
  void serviceShutdownClosesOwnedInputEvenBeforeTheSourceStarts() {
    var closed = new AtomicBoolean();
    var input =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            closed.set(true);
          }
        };
    var streams = new CatnapMvcStreams(new ProjectionService(ProjectionSettings.DEFAULT));
    var request = request();
    var emitter =
        streams.encodedNdjson(
            request,
            new MockHttpServletResponse(),
            input,
            streams.encoder(request, (value, out) -> {}));
    assertFalse(closed.get());
    streams.close();
    assertTrue(closed.get());
    assertThrows(
        CompletionException.class, () -> emitter.termination().toCompletableFuture().join());
  }

  @Test
  void shutdownClosesBlockedInputAndReleasesItsWorker() throws Exception {
    var reading = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    InputStream input =
        new InputStream() {
          @Override
          public int read() throws IOException {
            reading.countDown();
            try {
              closed.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IOException(interrupted);
            }
            throw new IOException("closed");
          }

          @Override
          public void close() {
            closed.countDown();
          }
        };
    try (var streams = new CatnapMvcStreams(new ProjectionService(ProjectionSettings.DEFAULT))) {
      var request = request();
      var emitter =
          streams.encodedNdjson(
              request,
              new MockHttpServletResponse(),
              input,
              streams.encoder(request, (value, out) -> {}));
      emitter.initialized();
      emitter.start();
      assertTrue(reading.await(5, TimeUnit.SECONDS));
      streams.close();
      assertTrue(closed.await(5, TimeUnit.SECONDS));
      assertNotNull(
          emitter
              .termination()
              .handle((value, failure) -> failure)
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void cleanInputEofClosesItsResourceAndCompletesNormally() throws Exception {
    var closed = new AtomicBoolean();
    var input =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            closed.set(true);
          }
        };
    try (var streams = new CatnapMvcStreams(new ProjectionService(ProjectionSettings.DEFAULT))) {
      var request = request();
      var emitter =
          streams.encodedNdjson(
              request,
              new MockHttpServletResponse(),
              input,
              streams.encoder(request, (value, out) -> {}));
      emitter.initialized();
      emitter.start();
      emitter.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertTrue(closed.get());
    }
  }
}
