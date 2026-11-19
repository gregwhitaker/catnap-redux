package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.examples.webflux.CatalogModels.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Business filtering and pagination happen before the framework serializes and Catnap projects. */
@Service
public class CatalogService {
  private final CatalogRepository repository;

  public CatalogService(CatalogRepository repository) {
    this.repository = repository;
  }

  public Product product(String id) {
    return repository.products().stream()
        .filter(product -> product.id().equals(id))
        .findFirst()
        .orElseThrow(
            () -> new CatalogException(404, "product_not_found", "No product with id: " + id));
  }

  public SearchResult search(Map<String, List<String>> parameters) {
    int page = integer(parameters, "page", 0, 0, Integer.MAX_VALUE);
    int size = integer(parameters, "size", 3, 1, 20);
    String query = single(parameters, "q", "").strip().toLowerCase(Locale.ROOT);
    String category = single(parameters, "category", "").strip().toLowerCase(Locale.ROOT);
    var matches =
        repository.products().stream()
            .filter(product -> category.isEmpty() || product.category().equals(category))
            .filter(product -> query.isEmpty() || searchableText(product).contains(query))
            .toList();
    int total = matches.size();
    long start = (long) page * size; // A valid large page number must not overflow into page zero.
    var items =
        start >= total
            ? List.<Product>of()
            : matches.subList((int) start, (int) Math.min(start + size, total));
    return new SearchResult(
        items, new Page(page, size, total, (total + size - 1) / size, start + size < total));
  }

  private static String searchableText(Product product) {
    return (product.name()
            + " "
            + product.description()
            + " "
            + product.brand()
            + " "
            + String.join(" ", product.tags()))
        .toLowerCase(Locale.ROOT);
  }

  private static String single(Map<String, List<String>> parameters, String name, String fallback) {
    var values = parameters.get(name);
    if (values == null || values.isEmpty()) return fallback;
    if (values.size() != 1)
      throw new CatalogException(400, "invalid_search", name + " must appear at most once");
    return values.get(0);
  }

  private static int integer(
      Map<String, List<String>> parameters, String name, int fallback, int min, int max) {
    String value = single(parameters, name, Integer.toString(fallback));
    try {
      if (!value.matches("[0-9]+")) throw new NumberFormatException();
      int number = Integer.parseInt(value);
      if (number < min || number > max) throw new NumberFormatException();
      return number;
    } catch (NumberFormatException failure) {
      throw new CatalogException(
          400, "invalid_search", name + " must be an integer between " + min + " and " + max);
    }
  }

  public static final class CatalogException extends RuntimeException {
    private final int status;
    private final String code;

    public CatalogException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public ApiError error() {
      return new ApiError(code, getMessage());
    }
  }
}
