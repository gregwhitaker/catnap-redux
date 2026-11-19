package com.github.gregwhitaker.catnap.examples.mvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The public catalog representation; Catnap sees its serialized snake_case field names. */
public final class CatalogModels {
  private CatalogModels() {}

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

  public record Price(BigDecimal amount, String currency) {}

  public record Image(
      String url, String size, String format, int width, int height, String altText) {}

  public record Variant(
      String sku, String color, String size, Price price, boolean inStock, int stock) {}

  public record SearchResult(List<Product> items, Page page) {}

  public record Page(int number, int size, int totalItems, int totalPages, boolean hasNext) {}

  public record ApiError(String code, String message) {}
}
