package com.github.gregwhitaker.catnap.testkit.probe;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.require;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Real HTTP catalog scenarios, shared by all five examples and independent artifact consumers. */
public final class CatalogResponseContract {
  private CatalogResponseContract() {}

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String PRODUCTS = "/api/products";
  private static final String PACK = PRODUCTS + "/trail-pack-28";
  private static final String CDN = "https://cdn.example.com/catalog/trail-pack-28/";

  // Written independently of the seed loader and projection engine.
  private static final String FULL_PACK =
      """
      {
        "id": "trail-pack-28",
        "name": "Trail Pack 28L",
        "description": "A weather-resistant daypack with a padded laptop sleeve and room for a full day on the trail.",
        "category": "packs",
        "brand": "Northline",
        "price": {"amount": 119.95, "currency": "USD"},
        "images": [
          {"url": "https://cdn.example.com/catalog/trail-pack-28/front-small.webp", "size": "small",
           "format": "webp", "width": 320, "height": 240, "alt_text": "Front view of trail pack 28"},
          {"url": "https://cdn.example.com/catalog/trail-pack-28/front-medium.webp", "size": "medium",
           "format": "webp", "width": 800, "height": 600, "alt_text": "Front view of trail pack 28"},
          {"url": "https://cdn.example.com/catalog/trail-pack-28/front-large.webp", "size": "large",
           "format": "webp", "width": 1600, "height": 1200, "alt_text": "Front view of trail pack 28"},
          {"url": "https://cdn.example.com/catalog/trail-pack-28/side-medium.jpeg", "size": "medium",
           "format": "jpeg", "width": 800, "height": 600, "alt_text": "Side view of trail pack 28"}
        ],
        "variants": [
          {"sku": "TR28-FOR", "color": "forest", "size": "one_size",
           "price": {"amount": 119.95, "currency": "USD"}, "in_stock": true, "stock": 18},
          {"sku": "TR28-INK", "color": "ink", "size": "one_size",
           "price": {"amount": 119.95, "currency": "USD"}, "in_stock": false, "stock": 0},
          {"sku": "TR28-CLY", "color": "clay", "size": "one_size",
           "price": {"amount": 129.95, "currency": "USD"}, "in_stock": true, "stock": 4}
        ],
        "tags": ["hiking", "commute", "recycled"],
        "attributes": {"material": "recycled nylon", "capacity": "28 L", "warranty": "2 years"}
      }
      """;

  public record Case(String name, String path, int status, String json) {}

  public static List<Case> cases() {
    var cases = new ArrayList<Case>();
    cases.add(ok("full product through the configured serializer", PACK, FULL_PACK));
    cases.add(ok("blank fields bypass projection", fields(PACK, " \t"), FULL_PACK));
    cases.add(
        ok(
            "product detail card",
            fields(PACK, "id,name,price"),
            """
        {"id":"trail-pack-28","name":"Trail Pack 28L","price":{"amount":119.95,"currency":"USD"}}
        """));
    cases.add(
        ok(
            "medium images, with predicate fields omitted",
            fields(PACK, "images(url)[size==medium]"),
            "{\"images\":[" + image("front-medium.webp") + "," + image("side-medium.jpeg") + "]}"));
    cases.add(
        ok(
            "image format, dimensions, and native field names",
            fields(PACK, "images(url,alt_text)[size==medium;format==webp;width>=640]"),
            """
        {"images":[{"url":"https://cdn.example.com/catalog/trail-pack-28/front-medium.webp",
                    "alt_text":"Front view of trail pack 28"}]}
        """));
    cases.add(
        ok(
            "image membership preserves source order",
            fields(PACK, "images(url)[size=in=(medium,large)]"),
            "{\"images\":["
                + image("front-medium.webp")
                + ","
                + image("front-large.webp")
                + ","
                + image("side-medium.jpeg")
                + "]}"));
    cases.add(
        ok(
            "variant boolean and numeric predicates",
            fields(PACK, "variants(sku,price)[in_stock==true;stock>=5]"),
            """
        {"variants":[{"sku":"TR28-FOR","price":{"amount":119.95,"currency":"USD"}}]}
        """));
    cases.add(
        ok(
            "grouped variant alternatives",
            fields(PACK, "variants(sku,color)[in_stock==true;(color==forest,color==clay)]"),
            """
        {"variants":[{"sku":"TR28-FOR","color":"forest"},{"sku":"TR28-CLY","color":"clay"}]}
        """));
    cases.add(
        ok(
            "variant membership",
            fields(PACK, "variants(sku)[color=in=(forest,clay)]"),
            "{\"variants\":[{\"sku\":\"TR28-FOR\"},{\"sku\":\"TR28-CLY\"}]}"));
    cases.add(
        ok(
            "sold-out variants",
            fields(PACK, "variants(sku,stock)[in_stock==false]"),
            "{\"variants\":[{\"sku\":\"TR28-INK\",\"stock\":0}]}"));
    cases.add(
        ok(
            "predicates use serialized names, not Java or Kotlin property names",
            fields(PACK, "variants(sku)[inStock==true]"),
            "{\"variants\":[]}"));
    cases.add(
        ok(
            "no matching images or variants",
            fields(PACK, "id,images(url)[size==original],variants(sku)[stock>1000]"),
            "{\"id\":\"trail-pack-28\",\"images\":[],\"variants\":[]}"));
    cases.add(
        ok(
            "an existing thumbnail does not become a medium image",
            fields(PRODUCTS + "/field-notebook", "id,images(url)[size==medium]"),
            "{\"id\":\"field-notebook\",\"images\":[]}"));
    cases.add(
        ok(
            "sold-out product retains an empty projected variant array",
            fields(PRODUCTS + "/trail-cap", "id,variants(sku)[in_stock==true]"),
            "{\"id\":\"trail-cap\",\"variants\":[]}"));
    cases.add(
        ok(
            "digital product with no images or variants",
            fields(
                PRODUCTS + "/gift-card",
                "id,price,images(url)[size==medium],variants(sku)[in_stock==true]"),
            "{\"id\":\"gift-card\",\"price\":{\"amount\":25,\"currency\":\"USD\"},\"images\":[],\"variants\":[]}"));

    cases.add(
        ok(
            "default first page",
            fields(PRODUCTS, "items(id),page"),
            page(0, 3, 10, true, "alpine-shell", "camp-lantern", "camp-mug")));
    cases.add(
        ok(
            "second page",
            fields(PRODUCTS + "?page=1", "items(id),page"),
            page(1, 3, 10, true, "field-notebook", "gift-card", "merino-tee")));
    cases.add(
        ok(
            "last partial page",
            fields(PRODUCTS + "?page=3", "items(id),page"),
            page(3, 3, 10, false, "trail-pack-28")));
    cases.add(
        ok(
            "page beyond the end",
            fields(PRODUCTS + "?page=4", "items(id),page"),
            page(4, 3, 10, false)));
    cases.add(
        ok(
            "large page does not overflow",
            fields(PRODUCTS + "?page=2147483647&size=20", "items(id),page"),
            page(Integer.MAX_VALUE, 20, 10, false)));
    cases.add(
        ok(
            "maximum page size",
            fields(PRODUCTS + "?size=20", "items(id),page"),
            page(
                0,
                20,
                10,
                false,
                "alpine-shell",
                "camp-lantern",
                "camp-mug",
                "field-notebook",
                "gift-card",
                "merino-tee",
                "ridge-boots",
                "summit-bottle",
                "trail-cap",
                "trail-pack-28")));
    cases.add(
        ok(
            "category filter before pagination",
            fields(PRODUCTS + "?category=%20APPAREL%20&size=2", "items(id),page"),
            page(0, 2, 3, true, "alpine-shell", "merino-tee")));
    cases.add(
        ok(
            "second category page",
            fields(PRODUCTS + "?category=apparel&size=2&page=1", "items(id),page"),
            page(1, 2, 3, false, "trail-cap")));
    cases.add(
        ok(
            "search and category combine",
            fields(PRODUCTS + "?q=%20TrAiL%20&category=apparel", "items(id),page"),
            page(0, 3, 2, false, "merino-tee", "trail-cap")));
    cases.add(
        ok(
            "search includes brand",
            fields(PRODUCTS + "?q=northline&size=20", "items(id),page"),
            page(0, 20, 4, false, "alpine-shell", "gift-card", "merino-tee", "trail-pack-28")));
    cases.add(
        ok(
            "search includes tags",
            fields(PRODUCTS + "?q=stationery", "items(id),page"),
            page(0, 3, 1, false, "field-notebook")));
    cases.add(
        ok(
            "empty search result",
            fields(PRODUCTS + "?q=unicorn", "items(id),page"),
            page(0, 3, 0, false)));
    cases.add(
        ok(
            "unknown category",
            fields(PRODUCTS + "?category=unknown", "items(id),page"),
            page(0, 3, 0, false)));
    cases.add(
        ok(
            "blank business filters",
            fields(PRODUCTS + "?q=%20&category=", "items(id),page"),
            page(0, 3, 10, true, "alpine-shell", "camp-lantern", "camp-mug")));
    cases.add(
        ok(
            "nested filters retain products and pagination metadata",
            fields(
                PRODUCTS,
                "items(id,images(url)[size==medium;format==webp],variants(sku)[in_stock==true;stock>=10]),page"),
            """
        {"items":[
          {"id":"alpine-shell","images":[{"url":"https://cdn.example.com/catalog/alpine-shell/front-medium.webp"}],
           "variants":[{"sku":"AS-MOS-M"}]},
          {"id":"camp-lantern","images":[{"url":"https://cdn.example.com/catalog/camp-lantern/front-medium.webp"}],
           "variants":[]},
          {"id":"camp-mug","images":[{"url":"https://cdn.example.com/catalog/camp-mug/front-medium.webp"}],
           "variants":[{"sku":"CM-CRM"}]}
        ],"page":{"number":0,"size":3,"total_items":10,"total_pages":4,"has_next":true}}
        """));
    cases.add(
        new Case(
            "missing product keeps its error response",
            fields(PRODUCTS + "/missing", "id"),
            404,
            "{\"code\":\"product_not_found\",\"message\":\"No product with id: missing\"}"));

    for (String value : List.of("-1", "one", "1.5", "", "2147483648")) {
      cases.add(
          error(
              "invalid page " + value,
              PRODUCTS + "?page=" + value,
              "page must be an integer between 0 and 2147483647"));
    }
    for (String value : List.of("0", "21", "lots", "")) {
      cases.add(
          error(
              "invalid size " + value,
              fields(PRODUCTS + "?size=" + value, "id"),
              "size must be an integer between 1 and 20"));
    }
    for (String parameter : List.of("page", "size", "q", "category")) {
      cases.add(
          error(
              "repeated " + parameter,
              PRODUCTS + "?" + parameter + "=1&" + parameter + "=2",
              parameter + " must appear at most once"));
    }
    cases.add(
        new Case(
            "legacy predicate operator rejected",
            fields(PACK, "images(url)[size=medium]"),
            400,
            null));
    cases.add(
        new Case(
            "incomplete predicate rejected", fields(PACK, "variants(sku)[stock>=]"), 400, null));
    cases.add(new Case("repeated fields rejected", PACK + "?fields=id&fields=name", 400, null));
    cases.add(
        ok(
            "documented combined detail filters",
            fields(
                PACK,
                "id,images(url,alt_text)[size==medium;format==webp],variants(sku,price)[in_stock==true;stock>=5]"),
            """
        {"id":"trail-pack-28",
         "images":[{"url":"https://cdn.example.com/catalog/trail-pack-28/front-medium.webp",
                    "alt_text":"Front view of trail pack 28"}],
         "variants":[{"sku":"TR28-FOR","price":{"amount":119.95,"currency":"USD"}}]}
        """));
    cases.add(
        ok(
            "documented catalog grid",
            fields(PRODUCTS + "?category=apparel&size=2", "items(id,name,price),page"),
            """
        {"items":[
          {"id":"alpine-shell","name":"Alpine Rain Shell","price":{"amount":189.95,"currency":"USD"}},
          {"id":"merino-tee","name":"Merino Trail Tee","price":{"amount":59.9,"currency":"USD"}}
        ],"page":{"number":0,"size":2,"total_items":3,"total_pages":2,"has_next":true}}
        """));
    cases.add(
        ok(
            "third page",
            fields(PRODUCTS + "?page=2", "items(id),page"),
            page(2, 3, 10, true, "ridge-boots", "summit-bottle", "trail-cap")));
    cases.add(
        ok(
            "search includes description",
            fields(PRODUCTS + "?q=rolled%20stainless", "items(id),page"),
            page(0, 3, 1, false, "camp-mug")));
    return List.copyOf(cases);
  }

  private static Case ok(String name, String path, String json) {
    return new Case(name, path, 200, json);
  }

  private static Case error(String name, String path, String message) {
    return new Case(
        name, path, 400, "{\"code\":\"invalid_search\",\"message\":\"" + message + "\"}");
  }

  private static String image(String file) {
    return "{\"url\":\"" + CDN + file + "\"}";
  }

  private static String page(int number, int size, int total, boolean hasNext, String... ids) {
    String items =
        String.join(
            ",", java.util.Arrays.stream(ids).map(id -> "{\"id\":\"" + id + "\"}").toList());
    return "{\"items\":["
        + items
        + "],\"page\":{\"number\":"
        + number
        + ",\"size\":"
        + size
        + ",\"total_items\":"
        + total
        + ",\"total_pages\":"
        + (total + size - 1) / size
        + ",\"has_next\":"
        + hasNext
        + "}}";
  }

  private static String fields(String path, String expression) {
    return path
        + (path.contains("?") ? "&" : "?")
        + "fields="
        + URLEncoder.encode(expression, StandardCharsets.UTF_8);
  }

  private static HttpResponse<String> get(String base, String path) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  public static void verify(String base, Case test, BiPredicate<String, String> equivalent)
      throws Exception {
    var response = get(base, test.path());
    require(
        response.statusCode() == test.status(),
        test.name() + " status: " + response.statusCode() + " body: " + response.body());
    if (test.json() != null) {
      require(
          equivalent.test(test.json(), response.body()), test.name() + " body: " + response.body());
    } else {
      require(
          response.body().contains("Invalid partial response query"),
          test.name() + " body: " + response.body());
      require(
          !response.body().contains("TR28-FOR"),
          "Invalid query must not return the original catalog");
    }
    String contentType = response.headers().firstValue("content-type").orElse("");
    require(
        contentType.startsWith("application/json")
            || contentType.startsWith("application/problem+json"),
        test.name() + " JSON content type");
  }

  /** Projections must not destroy the reusable catalog snapshot across requests. */
  public static void verifySourceUnchanged(String base, BiPredicate<String, String> equivalent)
      throws Exception {
    verify(base, ok("before filtering", PACK, FULL_PACK), equivalent);
    verify(
        base,
        ok(
            "filter all images and variants away",
            fields(PACK, "images(url)[size==missing],variants(sku)[stock>1000]"),
            "{\"images\":[],\"variants\":[]}"),
        equivalent);
    verify(base, ok("after filtering", PACK, FULL_PACK), equivalent);
  }
}
