# Quickstart

Catnap selects fields from the JSON your application already produces. Start with a working example, then choose a JSON binding or framework adapter for your application.

This walkthrough runs from the source checkout. To add Catnap to an existing application, use the [installation guide](INSTALLATION.md).

## Run your first projection

Clone the repository and launch the Spring MVC example:

```bash
git clone https://github.com/gregwhitaker/catnap-redux.git
cd catnap-redux
./gradlew :examples:spring-boot-mvc:bootRun
```

Use Java 17 or newer to launch the wrapper. Its checked-in daemon configuration selects Java 25 for the build; compilation and runtime requirements remain separate. Gradle can download missing toolchains. See [compatibility](COMPATIBILITY.md) for offline setup and the full matrix.

In another terminal, request the fields needed for a product card:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=id,name,price'
```

The response is:

```json
{"id":"trail-pack-28","name":"Trail Pack 28L","price":{"amount":119.95,"currency":"USD"}}
```

Omit `fields` to see the full product, including images, variants, and attributes. Catnap selects from the application's serialized JSON, so naming rules, ignored properties, and active views still apply. All five framework applications expose the same catalog API; their [walkthroughs](examples/README.md#catalog-workflows) also cover paginated search and variant availability.

## Choose a query dialect

All HTTP integrations default to the `CATNAP` dialect and the `fields` parameter. Catnap supports Google-style field selection:

```text
id,name,price(amount)
```

It also supports array filtering without any dialect configuration. Request the catalog example:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=images(url)[size==medium;format==webp]'
```

This returns `{"images":[{"url":"https://cdn.example.com/catalog/trail-pack-28/front-medium.webp"}]}`. The conditions inspect each original image's `size` and `format`, then return only the matching URL. The sample URL is illustrative; no image service is required.

Google and LinkedIn remain available as explicit options. Set `catnap.dialect=GOOGLE` for Google field selection without predicates, or configure LinkedIn:

```properties
catnap.dialect=LINKEDIN
```

Then request:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=id,name,price:(amount)'
```

Remove the dialect override or set `catnap.dialect=CATNAP` to return to the default. There is no automatic dialect detection. An omitted or ASCII-blank query uses the framework's normal response path. Invalid syntax and duplicate query parameters produce a client error. The [query specification](QUERY_SPECIFICATION.md) defines the complete behavior.

## Use the core directly

You can also project a native JSON tree without an HTTP framework. This example uses the Jackson 3 binding:

```java
import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import tools.jackson.databind.json.JsonMapper;

var json = new Jackson3Json(JsonMapper.builder().build());
var document = json.parse("""
    {"name":"ACME","price":{"amount":12.50,"currency":"USD"}}
    """);
var query = Catnap.compile("name,price(amount)", QueryDialect.CATNAP);
var result = query.evaluate(document, json.adapter());

System.out.println(json.write(result));
// {"name":"ACME","price":{"amount":12.50}}
```

Compile a query once and reuse it. Each evaluation has independent limits and result containers. `catnap-core` has no third-party runtime dependencies; the JSON library lives in the binding module. See the [core API](CORE_API.md) and [JSON bindings](JSON_INTEGRATIONS.md).

The repository includes a runnable standalone example:

```bash
./gradlew :examples:standalone:run
```

## Add an integration

Configure the repository using the [installation guide](INSTALLATION.md), then add the binding or framework module your application needs. The Catnap BOM aligns the library versions:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0"))
    implementation("com.github.gregwhitaker:catnap-jackson3")
}
```

Choose only the adapter your application needs:

| Application | Guide | Artifact |
|---|---|---|
| Spring MVC | [Spring integration](SPRING_INTEGRATION.md) | `catnap-spring-boot-starter` |
| Spring WebFlux | [Spring integration](SPRING_INTEGRATION.md#webflux-ndjson-and-sse) | `catnap-spring-boot-starter-webflux` |
| Micronaut | [Micronaut integration](MICRONAUT_INTEGRATION.md) | `catnap-micronaut` |
| Quarkus | [Quarkus integration](QUARKUS_INTEGRATION.md) | `catnap-quarkus` |
| Ktor | [Ktor integration](KTOR_INTEGRATION.md) | `catnap-ktor` |

The core and ordinary libraries target Java 17. The current Micronaut integration requires Java 25. The Spring starters target Boot 4 and Jackson 3; they are not Boot 3 starters.

## Project a stream

With the same example running, request an NDJSON stream:

```bash
curl --no-buffer --get 'http://localhost:8080/catnap/stream/ndjson' \
  --data-urlencode 'fields=payload(amount)'
```

The query applies independently to each record. Integrations also support JSON-bearing server-sent events through their explicit helpers. Read [streaming](STREAMING.md) before connecting an unbounded source.
