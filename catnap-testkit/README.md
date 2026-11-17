# Catnap testkit

Shared conformance fixtures and HTTP contract helpers for Catnap JSON bindings and framework integrations. Use this module as a test dependency when implementing an integration or verifying that an adapter preserves Catnap's query and response behavior.

The artifact targets Java 17 and has no third-party runtime dependencies. JSON fixture parsing belongs to the consuming tests; comparisons must preserve arbitrary-precision numbers.

## Add the test dependency

Configure a repository containing the development artifacts as described in [installation](../README.md#installation), then add:

```kotlin
dependencies {
    testImplementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    testImplementation("com.github.gregwhitaker:catnap-testkit")
}
```

Modules in this checkout can use `testImplementation(project(":catnap-testkit"))`.

## Query fixtures

The JAR packages UTF-8 JSON resources under `/com/github/gregwhitaker/catnap/conformance/v1/`. Begin with `manifest.json`, then load each listed resource with your test framework's classpath resource API.

| Resource | Coverage |
|---|---|
| `projection.json` | Expected projections and equivalent Google/LinkedIn expressions |
| `syntax-errors.json` | Error codes and exact UTF-16 source positions |
| `http-binding.json` | Absent, blank, repeated, and decoded query parameters |
| `streams.json` | Logical NDJSON records and SSE payload/metadata preservation |

The [corpus guide](src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) defines schemas and comparison rules. The [query specification](../docs/QUERY_SPECIFICATION.md) is the language contract. Expected results are independent fixtures; do not regenerate them using the evaluator under test.

## HTTP and streaming helpers

Helpers live in `com.github.gregwhitaker.catnap.testkit.probe` and use the JDK HTTP client against listening example servers.

| Helper | Responsibility |
|---|---|
| `FiniteResponseContract` | Query variants, public/internal serializer visibility, async responses, precision, statuses, and representation headers |
| `StreamingResponseContract` | NDJSON record content/count and SSE data/metadata on the wire |
| `StreamingLifecycleContract` | Early delivery, slow consumers, connection isolation, cancellation, and failure timing |
| `StreamLifecycleProbe` | Controlled publishers and per-connection observations used by lifecycle tests |

The contracts expect the endpoint layout and fixtures used by the [example applications](../examples/README.md), including `/catnap/quote` and `/catnap/quote-internal` for public and internal serialization views. Adapt an example's test setup when adding a framework. JSON equality is supplied by the consumer where required; the testkit does not impose a JSON library or JUnit dependency on applications.

## Verify

Run these commands from the repository root:

```bash
# Fixture integrity and testkit helper tests
./gradlew :catnap-testkit:test

# Execute projection and diagnostic fixtures against the core
./gradlew :catnap-core:test

# Run framework HTTP, wire, and lifecycle contracts
./gradlew streamingTest
```

Logical fixtures describe payload semantics. Real HTTP tests additionally verify framing, delivery, cancellation, and behavior before and after response commitment. See [extending Catnap](../docs/EXTENDING.md) for the complete integration workflow.
