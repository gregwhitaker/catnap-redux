# Catnap examples

Runnable applications showing field selection with native serializers, finite JSON responses, NDJSON, and server-sent events. These applications use the Catnap modules in this checkout; you do not need published packages to run them.

Run commands from the repository root with the checked-in Gradle wrapper. Java 17+ can launch the build; Gradle selects a Java 25 daemon and provisions compilation/test toolchains as needed. See [build prerequisites](../README.md#building-from-source).

## Standalone JSON

```bash
./gradlew :examples:standalone:run
```

The application parses a JSON document once and evaluates `name` and `price(amount)` against it:

```json
{"name":"ACME"}
{"price":{"amount":12.50}}
```

To project NDJSON from stdin, build the application distribution and run its launcher:

```bash
./gradlew :examples:standalone:installDist
examples/standalone/build/install/standalone/bin/standalone ndjson 'id' <<'NDJSON'
{"id":1,"name":"ACME"}
{"id":2,"name":"Example"}
NDJSON
```

```json
{"id":1}
{"id":2}
```

The transformer compiles once, reads and flushes one record at a time, and bounds each record to 1 MiB. The final input record must end with a newline. See the [standalone source](standalone/src/main/java/com/github/gregwhitaker/catnap/examples/standalone/Standalone.java) and [JSON integration guide](../docs/JSON_INTEGRATIONS.md).

## Framework applications

All five servers use port **8080** by default. Start one at a time and stop it with Ctrl+C before starting another.

| Framework | Start command | Source | Integration guide |
|---|---|---|---|
| Spring Boot MVC | `./gradlew :examples:spring-boot-mvc:bootRun` | [MVC](spring-boot-mvc/src/main/java/com/github/gregwhitaker/catnap/examples/mvc) | [Spring](../docs/SPRING_INTEGRATION.md) |
| Spring Boot WebFlux | `./gradlew :examples:spring-boot-webflux:bootRun` | [WebFlux](spring-boot-webflux/src/main/java/com/github/gregwhitaker/catnap/examples/webflux) | [Spring](../docs/SPRING_INTEGRATION.md) |
| Micronaut | `./gradlew :examples:micronaut:run` | [Micronaut](micronaut/src/main/java/com/github/gregwhitaker/catnap/examples/micronaut) | [Micronaut](../docs/MICRONAUT_INTEGRATION.md) |
| Quarkus | `./gradlew :examples:quarkus:quarkusDev` | [Quarkus](quarkus/src/main/java/com/github/gregwhitaker/catnap/examples/quarkus) | [Quarkus](../docs/QUARKUS_INTEGRATION.md) |
| Ktor | `./gradlew :examples:ktor:run` | [Ktor](ktor/src/main/kotlin/com/github/gregwhitaker/catnap/examples/ktor) | [Ktor](../docs/KTOR_INTEGRATION.md) |

Micronaut runs on Java 25. The other framework examples target Java 17. Dependency versions are pinned in the [version catalog](../gradle/libs.versions.toml) and project lockfiles; the [compatibility matrix](../docs/COMPATIBILITY.md) describes the tested combinations.

## Make a request

With any framework example running, use a second terminal to compare the full response with a selection:

```bash
curl 'http://localhost:8080/catnap/quote'

curl --get 'http://localhost:8080/catnap/quote' \
  --data-urlencode 'fields=payload(amount)'
```

The full response contains the public serialized fields:

```json
{"payload":{"display_name":"ACME","amount":"EUR:12.50"}}
```

The selected response contains only the requested amount:

```json
{"payload":{"amount":"EUR:12.50"}}
```

All examples use configured naming and custom serialization. Fields hidden by the serializer remain unavailable to a query. The default dialect is Catnap. Configure `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` to use either field-selection profile; LinkedIn uses `payload:(amount)`.

`/catnap/quote-internal` demonstrates an internal serialization view: `fields=payload(internal,ignored)` returns `{"payload":{"internal":"internal"}}`, while the same query on `/catnap/quote` returns `{"payload":{}}`. Java frameworks use their native JSON view annotations; Ktor uses an explicit internal response model. These example routes demonstrate serialization visibility, not application authorization.

The shared finite-response tests check both representations in all three dialects. Ktor also verifies that its configured JSON converter receives the declared generic types for ordinary, internal, list, and asynchronous responses, with and without projection.

## Filter array items

Array predicates work with the default `CATNAP` configuration. Start the application and request:

```bash
curl --get 'http://localhost:8080/catnap/catalog' \
  --data-urlencode 'fields=payload(amount)[display_name==ACME]'
```

```json
{"payload":[{"amount":"EUR:12.50"}]}
```

The catalog contains ACME and OTHER quotes. Predicates see serialized names and custom money strings, so `payload(display_name)[amount=="EUR:12.50"]` also selects ACME. Hidden fields remain unavailable. See the [RSQL subset](../docs/QUERY_SPECIFICATION.md#catnap-array-predicates) for supported operators and type rules.

The standalone NDJSON launcher also defaults to `CATNAP`; its optional final argument accepts `CATNAP`, `GOOGLE`, or `LINKEDIN`. In the MVC example, `/catnap/stream/images?fields=images(url)[size==medium]` demonstrates filtering inside two records, including an empty match; use `curl --get --data-urlencode` to encode the query brackets.

## Stream responses

These endpoints use the actual streaming adapters. `--no-buffer` makes curl display output as it arrives.

```bash
curl --no-buffer --get 'http://localhost:8080/catnap/stream/ndjson' \
  --data-urlencode 'fields=payload(amount)'

curl --no-buffer --get 'http://localhost:8080/catnap/stream/sse' \
  --data-urlencode 'fields=payload(amount)'

curl --no-buffer --get 'http://localhost:8080/catnap/stream/encoded' \
  --data-urlencode 'fields=label'
```

The NDJSON endpoint emits two projected quote records. The SSE endpoint projects JSON payloads while preserving event metadata, text, and control frames. The encoded endpoint demonstrates framing JSON records across byte chunks or input-stream reads. See each integration guide for its serializer callback, request binding, bounds, and cancellation behavior.

Additional endpoints exercise async responses, precision, disabled projection, errors, and stream lifecycle behavior. They support the shared test contracts and are examples to adapt to your application.

## Run the checks

```bash
# Standalone tree and NDJSON behavior
./gradlew :examples:standalone:test

# One framework's HTTP and streaming contracts
./gradlew :examples:spring-boot-mvc:test

# All five framework test suites
./gradlew streamingTest

# Build and test independent copies against locally published artifacts
./gradlew publicationSmokeTest
```

The [testkit](../catnap-testkit/README.md) supplies shared finite-response, wire, and lifecycle assertions. Native-image checks for Quarkus and Micronaut have [separate instructions](../docs/NATIVE_IMAGES.md).
