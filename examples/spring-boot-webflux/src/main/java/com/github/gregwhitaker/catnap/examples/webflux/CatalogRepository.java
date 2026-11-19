package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.examples.webflux.CatalogModels.Product;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Loads one deterministic snapshot at startup using the application's configured serializer. */
@Repository
public class CatalogRepository {
  private final List<Product> products;

  public CatalogRepository(JsonMapper mapper) {
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
