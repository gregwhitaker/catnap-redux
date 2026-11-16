package com.github.gregwhitaker.catnap.spring.webflux;

import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Validates the query before the native annotated-controller adapter invokes the endpoint. */
public final class CatnapWebFluxHandlerAdapter implements HandlerAdapter, Ordered {
  static final String ATTRIBUTE = CatnapWebFluxHandlerAdapter.class.getName() + ".projection";
  private final ProjectionService service;
  private final HandlerAdapter delegate;

  public CatnapWebFluxHandlerAdapter(ProjectionService service, HandlerAdapter delegate) {
    this.service = Objects.requireNonNull(service);
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static ProjectionRequest projection(ServerWebExchange exchange) {
    return exchange.getAttribute(ATTRIBUTE);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public boolean supports(Object handler) {
    return handler instanceof HandlerMethod && delegate.supports(handler);
  }

  @Override
  public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
    return Mono.defer(
        () -> {
          HandlerMethod method = (HandlerMethod) handler;
          CatnapProjection policy = method.getMethodAnnotation(CatnapProjection.class);
          if (policy == null)
            policy =
                AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), CatnapProjection.class);
          try {
            exchange
                .getAttributes()
                .put(
                    ATTRIBUTE,
                    service.bind(
                        exchange.getRequest().getQueryParams().get(service.settings().parameter()),
                        policy == null || policy.enabled()));
          } catch (QueryRequestException error) {
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            byte[] problem =
                ("{\"type\":\"about:blank\",\"title\":\"Invalid partial response query\",\"status\":400,\"code\":\""
                        + error.code()
                        + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return response
                .writeWith(Mono.just(response.bufferFactory().wrap(problem)))
                .then(Mono.empty());
          }
          return delegate.handle(exchange, handler);
        });
  }
}
