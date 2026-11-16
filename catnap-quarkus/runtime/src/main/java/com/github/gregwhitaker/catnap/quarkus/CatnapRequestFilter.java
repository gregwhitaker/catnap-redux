package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/** Binds the query after route matching and before invoking the endpoint. */
@Provider
@Priority(Priorities.USER)
public class CatnapRequestFilter implements ContainerRequestFilter {
  private static final com.fasterxml.jackson.databind.ObjectWriter PROBLEM_WRITER =
      com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writer();
  static final String PROJECTION = CatnapRequestFilter.class.getName() + ".projection";
  @Inject jakarta.inject.Provider<ProjectionService> services;
  @Context ResourceInfo resource;

  @Override
  public void filter(ContainerRequestContext request) throws java.io.IOException {
    ProjectionService service = services.get();
    CatnapProjection policy =
        resource.getResourceMethod() == null
            ? null
            : resource.getResourceMethod().getAnnotation(CatnapProjection.class);
    if (policy == null && resource.getResourceClass() != null)
      policy = resource.getResourceClass().getAnnotation(CatnapProjection.class);
    try {
      request.setProperty(
          PROJECTION,
          service.bind(
              request.getUriInfo().getQueryParameters().get(service.settings().parameter()),
              policy == null || policy.enabled()));
    } catch (QueryRequestException error) {
      request.abortWith(
          Response.status(400)
              .type("application/problem+json")
              .entity(
                  PROBLEM_WRITER.writeValueAsBytes(
                      Map.of(
                          "type",
                          "about:blank",
                          "title",
                          "Invalid partial response query",
                          "status",
                          400,
                          "code",
                          error.code())))
              .build());
    }
  }
}
