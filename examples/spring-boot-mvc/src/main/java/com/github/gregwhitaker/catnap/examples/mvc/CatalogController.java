package com.github.gregwhitaker.catnap.examples.mvc;

import com.github.gregwhitaker.catnap.examples.mvc.CatalogModels.*;
import com.github.gregwhitaker.catnap.examples.mvc.CatalogService.CatalogException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/{id}")
  public Product product(@PathVariable("id") String id) {
    return catalog.product(id);
  }

  @GetMapping
  public SearchResult search(@RequestParam MultiValueMap<String, String> parameters) {
    return catalog.search(parameters);
  }

  @ExceptionHandler(CatalogException.class)
  public ResponseEntity<ApiError> catalogError(CatalogException failure) {
    return ResponseEntity.status(failure.status()).body(failure.error());
  }
}
