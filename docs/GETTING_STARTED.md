# Quickstart

Catnap selects fields from the JSON your application already produces. Start with a working example, then choose a JSON binding or framework adapter for your application.

> **Development version:** This documentation describes `3.0.0-SNAPSHOT`, available from [GitHub Packages with authentication](PUBLICATION.md#consume-packages-from-github). The commands below run directly from a source checkout. The published snapshot is a development build.

## Run your first projection

From the Catnap repository, launch the Spring MVC example:

```bash
./gradlew :examples:spring-boot-mvc:bootRun
```

Use Java 17 or newer to launch the wrapper. Its checked-in daemon configuration selects Java 25 for the build; compilation and runtime requirements remain separate. Gradle can download missing toolchains. See [compatibility](COMPATIBILITY.md) for offline setup and the full matrix.

In another terminal, request only the serialized amount:

```bash
curl --get 'http://localhost:8080/catnap/quote' \
  --data-urlencode 'fields=payload(amount)'
```

The response is:

```json
{"payload":{"amount":"EUR:12.50"}}
```

The currency string comes from the application's configured serializer. Catnap selects from that serialized JSON, so naming rules, ignored properties, and active views still apply. Use `/catnap/*` for these integration examples.

## Choose a query dialect

The default Spring configuration uses the `fields` parameter and Google-style queries:

```text
payload(display_name,amount)
```

To use LinkedIn-style queries, configure the application explicitly:

```properties
catnap.dialect=LINKEDIN
```

Then request:

```bash
curl --get 'http://localhost:8080/catnap/quote' \
  --data-urlencode 'fields=payload:(display_name,amount)'
```

There is no automatic dialect detection. An omitted or ASCII-blank query uses the framework's normal response path. Invalid syntax and duplicate query parameters produce a client error. The [query specification](QUERY_SPECIFICATION.md) defines the complete behavior.

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
var query = Catnap.compile("name,price(amount)", QueryDialect.GOOGLE);
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

Use the [publication guide](PUBLICATION.md) to build and verify local artifacts or consume a version that has actually been published. The Catnap BOM aligns the library versions:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
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
