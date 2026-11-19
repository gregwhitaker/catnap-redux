package com.github.gregwhitaker.catnap.examples.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gregwhitaker.catnap.examples.quarkus.CatalogModels.Product;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Loads one deterministic snapshot at startup using the application's configured serializer. */
@ApplicationScoped
@Startup
public class CatalogRepository {
  private final List<Product> products;

  public CatalogRepository(ObjectMapper mapper) {
    try (var input =
        Objects.requireNonNull(
            CatalogRepository.class.getResourceAsStream(
                "/com/github/gregwhitaker/catnap/testkit/catalog/products.json"),
            "Missing catalog seed data")) {
      products =
          Arrays.stream(mapper.readValue(input, Product[].class))
              .sorted(Comparator.comparing(Product::id))
              .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot load catalog seed data", failure);
    }
  }

  public List<Product> products() {
    return products;
  }
}
