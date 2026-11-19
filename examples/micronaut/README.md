# Micronaut catalog example

A Micronaut storefront API using controllers, dependency injection, and Micronaut Serialization. The Catnap integration projects the JSON produced by the application's configured serializer.

Run a product-detail endpoint, paginated catalog search, and image/variant filtering against ten seeded outdoor-store products. There is no database or external service to configure.

## Run

From the repository root:

```bash
./gradlew :examples:micronaut:run
```

Use Java 17+ to launch Gradle; it provisions the build toolchains if needed. This application runs on Java 25. The server listens on **http://localhost:8080**. Stop other examples using that port first.

## Product detail

Start with the complete product to see descriptions, tags, attributes, price, image renditions, and stock by variant:

```bash
curl 'http://localhost:8080/api/products/trail-pack-28'
```

Then request a small product card:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=id,name,price'
```

```json
{"id":"trail-pack-28","name":"Trail Pack 28L","price":{"amount":119.95,"currency":"USD"}}
```

Omitting `fields` returns the full representation. Catnap selects fields from the serialized response; the controller keeps returning its normal domain model.

## Paginated search

Search apparel and return only the fields a results grid needs:

```bash
curl --get 'http://localhost:8080/api/products' \
  --data-urlencode 'category=apparel' \
  --data-urlencode 'page=0' \
  --data-urlencode 'size=2' \
  --data-urlencode 'fields=items(id,name,price),page'
```

```json
{
  "items": [
    {"id":"alpine-shell","name":"Alpine Rain Shell","price":{"amount":189.95,"currency":"USD"}},
    {"id":"merino-tee","name":"Merino Trail Tee","price":{"amount":59.9,"currency":"USD"}}
  ],
  "page": {"number":0,"size":2,"total_items":3,"total_pages":2,"has_next":true}
}
```

Repeat with `page=1` to get `trail-cap`. Add `q=trail` to match the Merino Trail Tee and Five-Panel Trail Cap before pagination.

| Parameter | Behavior |
|---|---|
| `q` | Case-insensitive substring of name, description, brand, or tags; blank means all products |
| `category` | Exact category slug, ignoring case and surrounding whitespace; blank means all categories |
| `page` | Zero-based integer, default `0`; a page beyond the end returns an empty `items` array |
| `size` | Integer from `1` to `20`, default `3` |
| `fields` | Optional Catnap projection using serialized JSON names |

Results always sort by product ID ascending. Business search and pagination run first. `total_items` counts the matching products before pagination or projection; `total_pages` is zero for no matches. Increment `page` while `has_next` is true and keep the other business parameters unchanged.

## Image and variant filtering

Build a detail response with medium WebP images and variants with at least five units in stock:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=id,images(url,alt_text)[size==medium;format==webp],variants(sku,price)[in_stock==true;stock>=5]'
```

```json
{
  "id": "trail-pack-28",
  "images": [
    {"url":"https://cdn.example.com/catalog/trail-pack-28/front-medium.webp","alt_text":"Front view of trail pack 28"}
  ],
  "variants": [
    {"sku":"TR28-FOR","price":{"amount":119.95,"currency":"USD"}}
  ]
}
```

Predicates inspect the original array items, so `size`, `format`, `in_stock`, and `stock` need not appear in the output. Use serialized names such as `in_stock`, not the model's `inStock`.

Membership and grouped alternatives work too:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=images(url)[size=in=(medium,large)],variants(sku,color)[in_stock==true;(color==forest,color==clay)]'
```

This returns three image URLs in their original order and the forest and clay variants. The ink variant is sold out. A semicolon means AND; a comma inside a predicate means OR; parentheses group alternatives.

The same nested filters work on search results:

```bash
curl --get 'http://localhost:8080/api/products' \
  --data-urlencode 'fields=items(id,images(url)[size==medium;format==webp],variants(sku)[in_stock==true;stock>=10]),page'
```

The first page still has three products and `total_items: 10`. The lantern remains in the result with `variants: []` because neither variant has ten units available. Nested filters do not change product membership or pagination metadata.

Try an empty collection:

```bash
curl --get 'http://localhost:8080/api/products/gift-card' \
  --data-urlencode 'fields=id,images(url)[size==medium],variants(sku)[in_stock==true]'
```

```json
{"id":"gift-card","images":[],"variants":[]}
```

Array predicates are part of the default `CATNAP` dialect. Set `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` in Micronaut configuration to opt into the other dialects. Google and LinkedIn support field selection, without array predicates. See the [query specification](../../docs/QUERY_SPECIFICATION.md#catnap-array-predicates) for the supported RSQL subset.

## Seed data and errors

All five apps load the same [seed JSON](../../catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/testkit/catalog/products.json) through their own configured serializer. It contains packs, apparel, footwear, camp gear, accessories, and a digital gift card, with exact decimal USD prices, multiple image formats, and available/sold-out variants. `field-notebook` has only a thumbnail; `trail-cap` is sold out; `gift-card` has no images or variants. The image URLs use an illustrative CDN domain; no image hosting is required or provided.

A missing product returns HTTP 404 with `code: "product_not_found"`. Invalid or repeated search parameters return HTTP 400 with `code: "invalid_search"`. For example:

```bash
curl --get 'http://localhost:8080/api/products' \
  --data-urlencode 'size=0' \
  --data-urlencode 'fields=id'
```

```json
{"code":"invalid_search","message":"size must be an integer between 1 and 20"}
```

Catnap leaves these error bodies intact. Repeated `fields` parameters or malformed projections also return HTTP 400 through the integration's query-error handler. Use `curl --get --data-urlencode` to preserve query punctuation.

## How it fits together

`CatalogController` returns `@Serdeable` records, so serialization metadata is generated at build time. `@JsonInclude(ALWAYS)` on the product and search-result models keeps empty collections visible. The eager `CatalogRepository` bean loads the seed at startup through the injected Micronaut `JsonMapper`. `CatalogService` owns search validation, filtering, and pagination. The application's Serde configuration produces snake_case names such as `in_stock`, `alt_text`, and `total_items`; a local `@Error` handler returns typed HTTP 400 and 404 responses.

| File | Responsibility |
|---|---|
| [CatalogController.java](src/main/java/com/github/gregwhitaker/catnap/examples/micronaut/CatalogController.java) | HTTP routes and error responses |
| [CatalogService.java](src/main/java/com/github/gregwhitaker/catnap/examples/micronaut/CatalogService.java) | Business search, validation, and pagination |
| [CatalogRepository.java](src/main/java/com/github/gregwhitaker/catnap/examples/micronaut/CatalogRepository.java) | Seed loading and stable product order |
| [CatalogModels.java](src/main/java/com/github/gregwhitaker/catnap/examples/micronaut/CatalogModels.java) | Native product, image, variant, page, and error models |
| [CatalogResponseTest.java](src/test/java/com/github/gregwhitaker/catnap/examples/micronaut/CatalogResponseTest.java) | Real HTTP checks with the default Catnap configuration |

The sample repository is an in-memory snapshot. In an application backed by a database, apply business filters and access controls in that layer before constructing the response. Catnap controls response shape, not database queries.

## Test

```bash
# Catalog scenarios against a listening server
./gradlew :examples:micronaut:test --tests '*CatalogResponseTest'

# Include the existing finite-response and streaming regression tests
./gradlew :examples:micronaut:test
```

The shared [catalog contract](../../catnap-testkit/src/main/java/com/github/gregwhitaker/catnap/testkit/probe/CatalogResponseContract.java) checks detail responses, stable pagination, search combinations, native serialized names, array predicates, empty matches, invalid requests, and reuse of the original data after projection. Independent Gradle-metadata and Maven-POM consumers run these same tests through `./gradlew publicationSmokeTest`.

The seed resource is registered for native-image packaging. Catalog models use generated Serde metadata, and the catalog tests participate in Micronaut's native test suite. See the [native-image guide](../../docs/NATIVE_IMAGES.md) for separate native verification commands.

The existing `/catnap/*` routes remain available for serializer, query-dialect, and streaming examples. See [all examples](../README.md) and the [Micronaut integration guide](../../docs/MICRONAUT_INTEGRATION.md) for those contracts and integration configuration.
