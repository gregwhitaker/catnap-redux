package com.github.gregwhitaker.catnap.examples.micronaut;

import com.github.gregwhitaker.catnap.examples.micronaut.CatalogModels.*;
import com.github.gregwhitaker.catnap.examples.micronaut.CatalogService.CatalogException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;

@Controller("/api/products")
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @Get("/{id}")
  public Product product(String id) {
    return catalog.product(id);
  }

  @Get
  public SearchResult search(HttpRequest<?> request) {
    return catalog.search(request.getParameters().asMap());
  }

  @Error(exception = CatalogException.class)
  public HttpResponse<ApiError> catalogError(CatalogException failure) {
    return HttpResponse.<ApiError>status(HttpStatus.valueOf(failure.status()))
        .body(failure.error());
  }
}
