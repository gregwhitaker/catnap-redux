package com.github.gregwhitaker.catnap.spring.webflux;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import io.netty.buffer.PooledByteBufAllocator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CatnapWebFluxFilterTest {
  private final NettyDataBufferFactory buffers =
      new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
  private final ProjectionSettings defaults = ProjectionSettings.DEFAULT;

  private MockServerWebExchange exchange(ProjectionSettings settings) {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/?fields=id").build());
    exchange
        .getAttributes()
        .put(
            CatnapWebFluxHandlerAdapter.ATTRIBUTE,
            new ProjectionService(settings).bind(List.of("id")));
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return exchange;
  }

  private NettyDataBuffer buffer(String text) {
    return buffers.allocateBuffer().write(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void oversizeSingleBufferIsRejectedAndReleasedBeforeCommit() {
    var settings =
        new ProjectionSettings(
            true,
            "fields",
            defaults.dialect(),
            defaults.compilation(),
            defaults.evaluation(),
            0,
            8);
    var exchange = exchange(settings);
    var buffer = buffer("{\"id\":1,\"secret\":2}");
    assertThrows(
        RuntimeException.class,
        () ->
            new CatnapWebFluxFilter(settings)
                .filter(exchange, target -> target.getResponse().writeWith(Mono.just(buffer)))
                .block());
    assertEquals(0, buffer.getNativeBuffer().refCnt());
    assertFalse(exchange.getResponse().isCommitted());
  }

  @Test
  void cancellationReleasesBufferedDataAndCancelsTheSource() {
    var exchange = exchange(defaults);
    var buffer = buffer("{\"id\":");
    var cancelled = new AtomicBoolean();
    Flux<DataBuffer> body =
        Flux.concat(Mono.just(buffer), Flux.<DataBuffer>never())
            .doOnCancel(() -> cancelled.set(true));
    var pending =
        new CatnapWebFluxFilter(defaults)
            .filter(exchange, target -> target.getResponse().writeWith(body))
            .subscribe();
    assertFalse(exchange.getResponse().isCommitted());
    pending.dispose();
    assertTrue(cancelled.get());
    assertEquals(0, buffer.getNativeBuffer().refCnt());
  }

  @Test
  void failuresReleasePooledBuffersWithoutWritingTheUnprojectedBody() {
    var exchange = exchange(defaults);
    var buffer = buffer("{\"secret\":1,");
    assertThrows(
        RuntimeException.class,
        () ->
            new CatnapWebFluxFilter(defaults)
                .filter(exchange, target -> target.getResponse().writeWith(Mono.just(buffer)))
                .block());
    assertEquals(0, buffer.getNativeBuffer().refCnt());
    assertFalse(exchange.getResponse().isCommitted());
  }

  @Test
  void nativeFiniteChunksAreJoinedWithinTheCapAndReleased() {
    var exchange = exchange(defaults);
    var first = buffer("{\"id\":\"é");
    var last = buffer("🐈\",\"private\":2}");
    new CatnapWebFluxFilter(defaults)
        .filter(exchange, target -> target.getResponse().writeWith(Flux.just(first, last)))
        .block();
    assertEquals("{\"id\":\"é🐈\"}", exchange.getResponse().getBodyAsString().block());
    assertEquals(0, first.getNativeBuffer().refCnt());
    assertEquals(0, last.getNativeBuffer().refCnt());
  }

  @Test
  void missingQueriesRetainTheOriginalResponse() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());
    new CatnapWebFluxFilter(defaults)
        .filter(
            exchange,
            target -> {
              assertSame(exchange.getResponse(), target.getResponse());
              return Mono.empty();
            })
        .block();
  }
}
