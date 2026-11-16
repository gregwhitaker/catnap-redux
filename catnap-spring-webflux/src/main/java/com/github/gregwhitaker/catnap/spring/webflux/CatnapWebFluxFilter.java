package com.github.gregwhitaker.catnap.spring.webflux;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/** Bounded finite-document projection after native WebFlux encoding, without blocking I/O. */
public final class CatnapWebFluxFilter implements WebFilter, Ordered {
  private final ProjectionSettings settings;
  private final JsonMapper mapper;
  private final Jackson3Json json;

  public CatnapWebFluxFilter(ProjectionSettings settings) {
    this.settings = Objects.requireNonNull(settings);
    mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(settings.evaluation().maxDepth() + 1)
                            .build())
                    .build())
            .build();
    json = new Jackson3Json(mapper);
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var values = exchange.getRequest().getQueryParams().get(settings.parameter());
    if (!settings.enabled()
        || values == null
        || (values.size() == 1
            && values
                .get(0)
                .chars()
                .allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n')))
      return chain.filter(exchange);
    var response =
        new ServerHttpResponseDecorator(exchange.getResponse()) {
          @Override
          public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            ProjectionRequest request = CatnapWebFluxHandlerAdapter.projection(exchange);
            int status = getStatusCode() == null ? 200 : getStatusCode().value();
            var contentType = getHeaders().getContentType();
            if (request == null
                || !request.active()
                || !ResponsePolicy.finiteJson(
                    contentType == null ? null : contentType.toString(),
                    status,
                    getHeaders().getFirst("Content-Encoding"))) return super.writeWith(body);
            ResponsePolicy.BODY_HEADERS.forEach(getHeaders()::remove);
            Charset charset =
                contentType.getCharset() == null
                    ? StandardCharsets.UTF_8
                    : contentType.getCharset();
            // Force a Flux so join's Mono fast path cannot skip the size bound.
            Mono<DataBuffer> projected =
                DataBufferUtils.join(Flux.from(body), settings.maxPayloadBytes())
                    .map(
                        buffer -> {
                          byte[] bytes;
                          try {
                            bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                          } finally {
                            DataBufferUtils.release(buffer);
                          }
                          var result = request.evaluate(json.parse(bytes), json.adapter());
                          try {
                            BoundedBuffer encoded = new BoundedBuffer(settings.maxPayloadBytes());
                            mapper.writeValue(new OutputStreamWriter(encoded, charset), result);
                            byte[] output = encoded.toByteArray();
                            getHeaders().setContentLength(output.length);
                            return bufferFactory().wrap(output);
                          } catch (IOException error) {
                            throw new IllegalStateException(
                                "JSON response projection failed", error);
                          }
                        });
            return super.writeWith(projected);
          }
        };
    return chain.filter(exchange.mutate().response(response).build());
  }
}
