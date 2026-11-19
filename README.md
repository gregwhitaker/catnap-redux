<picture>
  <source media="(prefers-color-scheme: dark)" srcset="brand/logos/colorways/catnap-white-slate-on-navy.png">
  <img src="brand/logos/catnap-logo.png" alt="Catnap" width="420">
</picture>

# Catnap

[![Build](https://github.com/gregwhitaker/catnap-redux/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gregwhitaker/catnap-redux/actions/workflows/ci.yml)

**Let clients choose which JSON fields your API returns.**

Catnap is a Java and Kotlin library for selecting fields from JSON. Add a framework integration, and clients can use a `fields` query parameter to select nested fields and filter array items. Your controllers keep returning their normal objects, and your application's serializer still controls field names and values.

For example, an endpoint that normally returns:

```json
{"id":42,"name":"Headphones","price":{"amount":79,"currency":"USD"},"inStock":true}
```

With `?fields=name,price(amount)`, it returns:

```json
{"name":"Headphones","price":{"amount":79}}
```

Use Catnap with **Spring MVC, Spring WebFlux, Micronaut, Quarkus, or Ktor**, or [directly with a JSON tree](#use-the-library-directly). NDJSON and server-sent events are supported through [streaming helpers](#streaming).

[Quick start](#quick-start) · [Installation](#installation) · [Query syntax](#query-syntax) · [Examples](examples/README.md) · [Documentation](https://gregwhitaker.github.io/catnap-redux/)

## Quick start

Try the Spring MVC example from source. You need Git and Java 17 or newer; the Gradle wrapper downloads the build toolchains it needs on the first run.

```bash
git clone https://github.com/gregwhitaker/catnap-redux.git
cd catnap-redux
./gradlew :examples:spring-boot-mvc:bootRun
```

Once the server starts, run this in another terminal:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=id,name,price'
```

The response contains just the fields needed for a product card:

```json
{"id":"trail-pack-28","name":"Trail Pack 28L","price":{"amount":119.95,"currency":"USD"}}
```

Omit `fields` to get the full product, including its description, images, variants, tags, and attributes. Catnap selects from the JSON produced by the application's configured serializer.

The default dialect also supports array filtering. Request only the URLs of medium WebP images:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=images(url)[size==medium;format==webp]'
```

```json
{"images":[{"url":"https://cdn.example.com/catalog/trail-pack-28/front-medium.webp"}]}
```

The [examples guide](examples/README.md#catalog-workflows) covers product detail, paginated search, and image/variant filtering in every supported framework, with a walkthrough for each app. To try Catnap without an HTTP server, run `./gradlew :examples:standalone:run` from the checkout.

## Installation

Catnap 3 is currently a development snapshot. To use the code in this checkout in your own application, follow the [local artifact setup](docs/PUBLICATION.md#use-locally-built-artifacts). Published snapshots are also available through [GitHub Packages](docs/PUBLICATION.md#consume-packages-from-github), which requires authentication.

Once your repository is configured, import the Catnap BOM to keep module versions aligned and add the integration for your application. For an existing Spring Boot 4 MVC application, add this to `build.gradle.kts`:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter")
}
```

The Spring Boot starters automatically enable `fields` on annotated JSON controllers. For example, requesting `/your-endpoint?fields=id,name` selects those fields from its normal response. Other frameworks use the setup described in their guides:

| Your application | Module | Setup guide |
|---|---|---|
| Spring Boot MVC | `catnap-spring-boot-starter` | [MVC starter](catnap-spring-boot-starter/README.md) |
| Spring Boot WebFlux | `catnap-spring-boot-starter-webflux` | [WebFlux starter](catnap-spring-boot-starter-webflux/README.md) |
| Micronaut | `catnap-micronaut` | [Micronaut](docs/MICRONAUT_INTEGRATION.md) |
| Quarkus | `catnap-quarkus` | [Quarkus](docs/QUARKUS_INTEGRATION.md) |
| Ktor | `catnap-ktor` | [Ktor](docs/KTOR_INTEGRATION.md) |
| Jackson 2 or Jackson 3 | `catnap-jackson2` or `catnap-jackson3` | [JSON bindings](docs/JSON_INTEGRATIONS.md) |
| Kotlin serialization | `catnap-kotlinx-json` | [Kotlin serialization](docs/JSON_INTEGRATIONS.md#kotlin-serialization) |

The core and general-purpose libraries require **Java 17+**. The Spring starters use **Spring Boot 4 and Jackson 3**; Micronaut requires **Java 25**. See the [compatibility matrix](docs/COMPATIBILITY.md) for supported versions.

## Query syntax

HTTP integrations use the **`CATNAP` dialect** and the **`fields` parameter** by default.

| What you want | Query |
|---|---|
| Two fields | `id,name` |
| Nested fields | `price(amount,currency)` |
| One nested field | `price/amount` |
| Fields from every array item | `items(id,name)` |
| URLs for medium images only | `images(url)[size==medium]` |
| Medium or large images | `images(url)[size=in=(medium,large)]` |
| Medium images at least 640 pixels wide | `images(url)[size==medium;width>=640]` |
| A field from every value in a map | `byId/*/name` |
| The entire document | `*` |

In `images(url)[size==medium]`, the condition reads each image's original `size`, then returns only `url` for matching images. Put the condition **after** the selected field's child list. Use `==` for equality, `;` for AND, and `,` inside brackets for OR. Use `curl --get --data-urlencode` as above to encode query punctuation correctly.

A few rules to know:

- Field names match the serialized JSON, including any custom naming rules. Fields hidden by the serializer stay hidden.
- Missing fields are omitted. Arrays keep their order and item count unless an explicit predicate filters them; an array with no matches remains `[]`.
- Omitting `fields` returns the normal response. Invalid queries and repeated `fields` parameters return HTTP 400.
- Catnap works on the response JSON. Apply database filtering and access controls in your application.

Google and LinkedIn dialects remain available: set `catnap.dialect=GOOGLE` for Google-style field selection, or `catnap.dialect=LINKEDIN` for queries such as `price:(amount)`. These dialects do not support array predicates. Ktor also exposes the dialect in its installation DSL.

See the [query reference](docs/QUERY_SPECIFICATION.md) for quoted field names, the complete RSQL predicate subset, comparison rules, and configurable limits.

## Use the library directly

For use without a web framework, add `com.github.gregwhitaker:catnap-jackson3` with the BOM shown above. This complete example parses JSON, compiles a query, and writes the selected result:

```java
import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import tools.jackson.databind.json.JsonMapper;

public class ProjectionExample {
    public static void main(String[] args) {
        var json = new Jackson3Json(JsonMapper.builder().build());
        var document = json.parse("""
            {"name":"Headphones","price":{"amount":79,"currency":"USD"}}
            """);
        var query = Catnap.compile("name,price(amount)", QueryDialect.CATNAP);
        var result = query.evaluate(document, json.adapter());

        System.out.println(json.write(result));
        // {"name":"Headphones","price":{"amount":79}}
    }
}
```

Compile once and reuse the query across documents or threads. Evaluation preserves the input tree. The core API requires an explicit dialect; the HTTP integrations supply their configured default. See [JSON bindings](docs/JSON_INTEGRATIONS.md) for Jackson 2 and Kotlin serialization, or the [core API](docs/CORE_API.md) for custom tree adapters, caching, and limits.

## Streaming

Use the same queries to select fields within each NDJSON record or JSON payload in a server-sent event. Array predicates filter within a payload; they never drop the enclosing record or event. SSE IDs, event names, and other metadata are preserved.

Streaming endpoints need an explicit framework helper. Start with the [streaming guide](docs/STREAMING.md) and [runnable examples](examples/README.md#stream-responses). The standalone example can also [transform NDJSON from stdin](docs/JSON_INTEGRATIONS.md#standalone-tree-and-ndjson-examples).

## Building from source

Run from the repository root using Java 17+ and the Gradle wrapper (`gradlew.bat` on Windows):

```bash
./gradlew build compatibilityTest
```

The build itself uses a Java 25 daemon; Gradle can provision missing toolchains. This is separate from the library's Java 17 runtime baseline. See [compatibility and toolchain setup](docs/COMPATIBILITY.md) for offline configuration.

## Contributing

When reporting an issue, include the query, input JSON, expected and actual results, and your framework, serializer, and Java versions. For code changes, start with the [contributor guide](AGENTS.md), run the affected module's checks, then run `./gradlew build compatibilityTest` before submitting.

See [extending Catnap](docs/EXTENDING.md) for new integrations, [publication verification](docs/PUBLICATION.md) for consumer checks, and the [changelog](CHANGELOG.md) for recent changes.

## License

Catnap is licensed under the [Apache License 2.0](LICENSE.txt).
