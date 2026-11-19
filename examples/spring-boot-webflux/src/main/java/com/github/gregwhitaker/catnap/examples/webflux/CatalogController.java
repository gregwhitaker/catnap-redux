package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.examples.webflux.CatalogModels.*;
import com.github.gregwhitaker.catnap.examples.webflux.CatalogService.CatalogException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/products")
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/{id}")
  public Mono<Product> product(@PathVariable("id") String id) {
    return Mono.fromSupplier(() -> catalog.product(id));
  }

  @GetMapping
  public Mono<SearchResult> search(@RequestParam MultiValueMap<String, String> parameters) {
    return Mono.fromSupplier(() -> catalog.search(parameters));
  }

  @ExceptionHandler(CatalogException.class)
  public ResponseEntity<ApiError> catalogError(CatalogException failure) {
    return ResponseEntity.status(failure.status()).body(failure.error());
  }
}
