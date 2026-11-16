package com.github.gregwhitaker.catnap.spring.mvc;

import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Compiles the connection's query before invoking a controller or starting an asynchronous source.
 */
public final class CatnapMvcInterceptor implements HandlerInterceptor {
  static final String REQUEST_ATTRIBUTE = CatnapMvcInterceptor.class.getName() + ".projection";
  private final ProjectionService service;

  public CatnapMvcInterceptor(ProjectionService service) {
    this.service = Objects.requireNonNull(service);
  }

  /** Returns the immutable request state, including across MVC asynchronous dispatches. */
  public static ProjectionRequest projection(HttpServletRequest request) {
    return (ProjectionRequest) request.getAttribute(REQUEST_ATTRIBUTE);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    if (!(handler instanceof HandlerMethod method) || projection(request) != null) return true;
    CatnapProjection policy = method.getMethodAnnotation(CatnapProjection.class);
    if (policy == null)
      policy =
          AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), CatnapProjection.class);
    String[] values = request.getParameterValues(service.settings().parameter());
    try {
      request.setAttribute(
          REQUEST_ATTRIBUTE,
          service.bind(
              values == null ? null : Arrays.asList(values), policy == null || policy.enabled()));
      return true;
    } catch (QueryRequestException error) {
      response.setStatus(400);
      response.setContentType("application/problem+json");
      response.setCharacterEncoding("UTF-8");
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Invalid partial response query\",\"status\":400,\"code\":\""
                  + error.code()
                  + "\"}");
      return false;
    }
  }
}
