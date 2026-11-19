package com.github.gregwhitaker.catnap.examples.quarkus;

import com.github.gregwhitaker.catnap.examples.quarkus.CatalogModels.*;
import com.github.gregwhitaker.catnap.examples.quarkus.CatalogService.CatalogException;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

@Path("/api/products")
@Produces("application/json")
public class CatalogResource {
  private final CatalogService catalog;

  public CatalogResource(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GET
  @Path("/{id}")
  public Uni<Product> product(@PathParam("id") String id) {
    return Uni.createFrom().item(() -> catalog.product(id));
  }

  @GET
  public Uni<SearchResult> search(@Context UriInfo uri) {
    return Uni.createFrom().item(() -> catalog.search(uri.getQueryParameters()));
  }

  @ServerExceptionMapper
  public RestResponse<ApiError> catalogError(CatalogException failure) {
    return RestResponse.status(Response.Status.fromStatusCode(failure.status()), failure.error());
  }
}
