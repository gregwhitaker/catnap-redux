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
| `projection.json` | Expected projections across all dialects and CATNAP-only predicate cases |
| `syntax-errors.json` | Error codes and exact UTF-16 source positions |
| `http-binding.json` | Absent, blank, repeated, and decoded query parameters |
| `streams.json` | Logical NDJSON records and SSE payload/metadata preservation |

The [corpus guide](src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) defines schemas and comparison rules. The [query specification](../docs/QUERY_SPECIFICATION.md) is the language contract. Expected results are independent fixtures; do not regenerate them using the evaluator under test.

Run every query variant in each fixture. Field-selection cases cover `CATNAP`, `GOOGLE`, and `LINKEDIN`; predicate cases use `CATNAP` only. Array filtering may reduce payload arrays, while logical NDJSON record and SSE event counts must remain unchanged.

## HTTP and streaming helpers

Helpers live in `com.github.gregwhitaker.catnap.testkit.probe` and use the JDK HTTP client against listening example servers.

| Helper | Responsibility |
|---|---|
| `FiniteResponseContract` | Query variants, public/internal serializer visibility, async responses, precision, statuses, and representation headers |
| `CatalogResponseContract` | Product detail, paginated search, image/variant predicates, empty collections, errors, and unchanged source data across requests |
| `StreamingResponseContract` | NDJSON record content/count and SSE data/metadata on the wire |
| `StreamingLifecycleContract` | Early delivery, slow consumers, connection isolation, cancellation, and failure timing |
| `StreamLifecycleProbe` | Controlled publishers and per-connection observations used by lifecycle tests |

The contracts expect the endpoint layout and fixtures used by the [example applications](../examples/README.md), including `/catnap/quote` and `/catnap/quote-internal` for public and internal serialization views. Adapt an example's test setup when adding a framework. JSON equality is supplied by the consumer where required; the testkit does not impose a JSON library or JUnit dependency on applications.

Use `FiniteResponseContract.catnapCases(parameter)` against the default configuration. It includes field-selection cases and `/catnap/catalog` predicates covering serialized names, custom scalar values, hidden fields, and empty matches. Exercise explicit `GOOGLE` and `LINKEDIN` configurations with `cases(parameter, false)` and `cases(parameter, true)` respectively, and verify that both reject predicates.

`CatalogResponseContract.cases()` targets `/api/products` and `/api/products/{id}` with the default Catnap dialect and `fields` parameter. The examples also call `verifySourceUnchanged` after filtering. These tests run against each framework's listening server and in both independent publication-consumer modes.

The JAR includes a shared [catalog seed](src/main/resources/com/github/gregwhitaker/catnap/testkit/catalog/products.json) at `/com/github/gregwhitaker/catnap/testkit/catalog/products.json`. Each example loads it with its own configured serializer into framework-local models. This sample data is separate from the versioned query conformance corpus; the catalog contract's expected responses are written independently of the seed loader and evaluator.

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
