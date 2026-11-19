package com.github.gregwhitaker.catnap.examples.micronaut;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The public catalog representation; Catnap sees its serialized snake_case field names. */
public final class CatalogModels {
  private CatalogModels() {}

  @Serdeable
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Product(
      String id,
      String name,
      String description,
      String category,
      String brand,
      Price price,
      List<Image> images,
      List<Variant> variants,
      List<String> tags,
      Map<String, String> attributes) {
    public Product {
      images = List.copyOf(images);
      variants = List.copyOf(variants);
      tags = List.copyOf(tags);
      attributes = Map.copyOf(attributes);
    }
  }

  @Serdeable
  public record Price(BigDecimal amount, String currency) {}

  @Serdeable
  public record Image(
      String url, String size, String format, int width, int height, String altText) {}

  @Serdeable
  public record Variant(
      String sku, String color, String size, Price price, boolean inStock, int stock) {}

  @Serdeable
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SearchResult(List<Product> items, Page page) {}

  @Serdeable
  public record Page(int number, int size, int totalItems, int totalPages, boolean hasNext) {}

  @Serdeable
  public record ApiError(String code, String message) {}
}
