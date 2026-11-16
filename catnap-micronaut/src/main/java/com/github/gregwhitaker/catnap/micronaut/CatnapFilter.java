package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.body.MediaTypeProvider;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.web.router.RouteInfo;
import jakarta.inject.Named;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Binds before route execution and wraps the selected native finite response writer. */
@ServerFilter("/**")
@Requires(property = "catnap.enabled", notEquals = "false", defaultValue = "true")
public final class CatnapFilter {
  static final String ATTRIBUTE = CatnapFilter.class.getName() + ".projection";
  private final ProjectionService service;
  private final MessageBodyHandlerRegistry writers;
  private final RouteExecutor routes;
  private final MicronautJson json;
  private final Scheduler blocking;
  private final ConversionService conversionService;

  public CatnapFilter(
      ProjectionService service,
      MessageBodyHandlerRegistry writers,
      RouteExecutor routes,
      @Named(TaskExecutors.BLOCKING) ExecutorService executor,
      ConversionService conversionService) {
    this.service = service;
    this.writers = writers;
    this.routes = routes;
    json = new MicronautJson(service.settings().evaluation().maxDepth());
    blocking = Schedulers.fromExecutorService(executor);
    this.conversionService = conversionService;
  }

  public static ProjectionRequest projection(HttpRequest<?> request) {
    return request.getAttribute(ATTRIBUTE, ProjectionRequest.class).orElse(null);
  }

  @RequestFilter
  @Nullable
  public HttpResponse<?> bind(HttpRequest<?> request, RouteInfo<?> route) {
    boolean enabled =
        route.getAnnotationMetadata().booleanValue(CatnapProjection.class, "enabled").orElse(true);
    try {
      request.setAttribute(
          ATTRIBUTE,
          service.bind(request.getParameters().getAll(service.settings().parameter()), enabled));
      return null;
    } catch (QueryRequestException error) {
      return HttpResponse.badRequest(
              Map.of(
                  "type",
                  "about:blank",
                  "title",
                  "Invalid partial response query",
                  "status",
                  400,
                  "code",
                  error.code()))
          .contentType(MediaType.APPLICATION_JSON_PROBLEM_TYPE);
    }
  }

  @ResponseFilter
  @SuppressWarnings("unchecked")
  public Mono<MutableHttpResponse<Object>> project(
      HttpRequest<?> request, MutableHttpResponse<Object> response, RouteInfo<?> route) {
    ProjectionRequest projection = projection(request);
    if (projection == null || !projection.active()) return Mono.just(response);
    if (response.getAttribute(CatnapMicronautStreams.ATTRIBUTE, Boolean.class).orElse(false)) {
      ResponsePolicy.BODY_HEADERS.forEach(response.getHeaders()::remove);
      return Mono.just(response);
    }
    Object value = response.body();
    MediaType mediaType =
        response
            .getContentType()
            .orElseGet(
                () ->
                    value instanceof MediaTypeProvider provider
                        ? provider.getMediaType()
                        : routes.resolveDefaultResponseContentType(request, route));
    if (!ResponsePolicy.finiteJson(
        mediaType.toString(), response.code(), response.getHeaders().get("Content-Encoding")))
      return Mono.just(response);
    if (value == null) return Mono.just(response);
    if (Publishers.isConvertibleToPublisher(value))
      return finitePublisher(response, route, projection, mediaType, value);
    Argument<Object> type = (Argument<Object>) route.getResponseBodyType();
    MessageBodyWriter<Object> writer = response.getBodyWriter().orElse(null);
    if (!type.isInstance(value)) type = (Argument<Object>) Argument.of(value.getClass());
    if (writer == null || !writer.isWriteable(type, mediaType))
      writer = writers.getWriter(type, List.of(mediaType)).createSpecific(type);
    response.bodyWriter(new ProjectingBodyWriter<>(writer, projection, json));
    return Mono.just(response);
  }

  @SuppressWarnings("unchecked")
  private Mono<MutableHttpResponse<Object>> finitePublisher(
      MutableHttpResponse<Object> response,
      RouteInfo<?> route,
      ProjectionRequest projection,
      MediaType mediaType,
      Object publisher) {
    return Mono.defer(
            () -> {
              BoundedBuffer capture = new BoundedBuffer(projection.settings().maxPayloadBytes());
              boolean array = route.isResponseBodyJsonFormattable();
              boolean[] first = {true};
              try {
                if (array) capture.write('[');
              } catch (IOException error) {
                return Mono.error(error);
              }
              Argument<Object> declared = (Argument<Object>) route.getResponseBodyType();
              return Flux.from(Publishers.convertToPublisher(conversionService, publisher))
                  .concatMap(
                      item -> {
                        Argument<Object> type = declared;
                        MessageBodyWriter<Object> writer = response.getBodyWriter().orElse(null);
                        if (!type.isInstance(item))
                          type = (Argument<Object>) Argument.of(item.getClass());
                        if (writer == null || !writer.isWriteable(type, mediaType))
                          writer = writers.getWriter(type, List.of(mediaType)).createSpecific(type);
                        MessageBodyWriter<Object> selected = writer;
                        Argument<Object> selectedType = type;
                        Mono<Object> encode =
                            Mono.fromCallable(
                                () -> {
                                  if (array && !first[0]) capture.write(',');
                                  first[0] = false;
                                  selected.writeTo(
                                      selectedType,
                                      mediaType,
                                      item,
                                      response.getHeaders(),
                                      capture);
                                  return item;
                                });
                        return selected.isBlocking() ? encode.subscribeOn(blocking) : encode;
                      },
                      1)
                  .then(
                      Mono.fromCallable(
                          () -> {
                            if (array) capture.write(']');
                            byte[] bytes =
                                json.project(
                                    capture.toByteArray(),
                                    projection,
                                    MessageBodyWriter.findCharset(mediaType, response.getHeaders())
                                        .orElse(StandardCharsets.UTF_8));
                            ResponsePolicy.BODY_HEADERS.forEach(response.getHeaders()::remove);
                            response.contentType(mediaType).body(bytes);
                            response
                                .getHeaders()
                                .set("Content-Length", Integer.toString(bytes.length));
                            // The framework's byte-array writer emits the already projected finite
                            // document.
                            response.bodyWriter(
                                (MessageBodyWriter<Object>)
                                    (MessageBodyWriter<?>)
                                        writers.getWriter(
                                            Argument.of(byte[].class), List.of(mediaType)));
                            return response;
                          }));
            })
        .onErrorMap(
            error -> {
              // Micronaut normally classifies CodecException from a filter as malformed client
              // input.
              // These bytes were produced by the server's response writer, so failure is a server
              // error.
              var failure =
                  new HttpStatusException(
                      HttpStatus.INTERNAL_SERVER_ERROR, "JSON response projection failed");
              failure.initCause(error);
              return failure;
            });
  }
}
