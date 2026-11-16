<picture>
  <source media="(prefers-color-scheme: dark)" srcset="brand/logos/colorways/catnap-white-slate-on-navy.png">
  <img src="brand/logos/catnap-logo.png" alt="Catnap" width="420">
</picture>

# Catnap

**JSON partial responses for Java and Kotlin.**

Catnap lets API clients request the JSON fields they need. Add a `fields` query parameter to select nested properties, apply selections to arrays, and project individual NDJSON records or JSON payloads in server-sent events. Use it directly with a JSON tree or through an adapter for Spring MVC, Spring WebFlux, Micronaut, Quarkus, or Ktor.

For a response like this:

```json
{"name":"ACME","price":{"amount":12.50,"currency":"USD"},"inStock":true}
```

A Google-style selection of `name,price(amount)` returns:

```json
{"name":"ACME","price":{"amount":12.50}}
```

Catnap projects the JSON produced by your configured serializer, preserving naming rules, visibility, custom serializers, and numeric precision. Field selection does not fetch data or replace application authorization.

[Quick start](#quick-start) · [Installation](#installation) · [Examples](examples/README.md) · [Documentation](docs/README.md) · [Migrating from Catnap 2](docs/MIGRATION.md) · [Brand assets](brand/README.md)

## Documentation website

The [documentation website](website/README.md) builds directly from the Markdown guides and includes a [quickstart](docs/GETTING_STARTED.md), local search, query examples, and framework guides. Preview it with Node 22 or newer:

```bash
cd website
npm ci
npm run dev
```

The [Documentation workflow](.github/workflows/docs.yml) validates pull requests and deploys default-branch documentation changes to GitHub Pages. Set **Settings → Pages → Source** to **GitHub Actions**, then run the workflow. See the [deployment instructions](website/README.md#deploy-with-github-actions).

## Project status

This is the **3.0.0-SNAPSHOT** development version. The core, JSON bindings, framework integrations, and streaming helpers are implemented and verified locally. Public release publication and hosted CI execution are not part of the recorded verification. Use the source examples or locally built artifacts below to try this version.

The [compatibility matrix](docs/COMPATIBILITY.md) lists tested versions and runtime requirements. The [release audit](docs/RELEASE_AUDIT.md) records verification scope and known limitations, including a historical WebFlux drain timeout whose cause remains unknown. Catnap 3 introduces a new API; see the [migration guide](docs/MIGRATION.md) for differences from Catnap 2.

## Quick start

Run these commands from a checkout with Java 17 or newer. The Gradle wrapper selects a Java 25 build daemon and can download missing toolchains; see [building from source](#building-from-source).

To try projection without an HTTP server:

```bash
./gradlew :examples:standalone:run
```

The application parses one document and reuses it for two selections:

```json
{"name":"ACME"}
{"price":{"amount":12.50}}
```

To try an API, start the Spring MVC example:

```bash
./gradlew :examples:spring-boot-mvc:bootRun
```

Then, from another terminal:

```bash
curl --get 'http://localhost:8080/catnap/quote' \
  --data-urlencode 'fields=payload(amount)'
```

```json
{"payload":{"amount":"EUR:12.50"}}
```

The currency string comes from the example's custom serializer. Omit `fields` to receive the normal response. The [examples guide](examples/README.md) includes commands for all five frameworks, NDJSON, SSE, and a standalone stdin transformer.

## Installation

All artifacts use the `com.github.gregwhitaker` group. Import `catnap-bom` to align Catnap versions, then choose the module for your application.

For this development checkout, first build a local Maven repository:

```bash
./gradlew publishVerificationArtifacts
```

This writes artifacts to `build/repository` inside the checkout. In a separate Gradle application's `build.gradle.kts`, replace the absolute path below with that directory:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("/absolute/path/to/catnap/build/repository")
        content { includeGroup("com.github.gregwhitaker") }
    }
}

dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-jackson3")
}
```

If your application centralizes repositories, put the repository declarations in `settings.gradle.kts` under `dependencyResolutionManagement`. The BOM aligns Catnap libraries without importing a framework BOM. The [publication guide](docs/PUBLICATION.md) covers Maven, independent consumer verification, and authenticated GitHub Packages consumption when the requested version has been published.

| Application | Module | Guide |
|---|---|---|
| Spring Boot MVC | `catnap-spring-boot-starter` | [MVC starter](catnap-spring-boot-starter/README.md) |
| Spring Boot WebFlux | `catnap-spring-boot-starter-webflux` | [WebFlux starter](catnap-spring-boot-starter-webflux/README.md) |
| Micronaut | `catnap-micronaut` | [Micronaut integration](docs/MICRONAUT_INTEGRATION.md) |
| Quarkus | `catnap-quarkus` | [Quarkus integration](docs/QUARKUS_INTEGRATION.md) |
| Ktor | `catnap-ktor` | [Ktor integration](docs/KTOR_INTEGRATION.md) |
| Jackson 2 or Jackson 3 | `catnap-jackson2` or `catnap-jackson3` | [JSON bindings](docs/JSON_INTEGRATIONS.md) |
| Kotlin serialization | `catnap-kotlinx-json` | [JSON bindings](docs/JSON_INTEGRATIONS.md#kotlin-serialization) |
| Custom JSON tree | `catnap-core` | [Core API](docs/CORE_API.md) |

The Spring starters target Boot 4 and Jackson 3. Micronaut requires Java 25; the core and other libraries target Java 17. See the [tested matrix](docs/COMPATIBILITY.md#implemented-framework-and-serializer-combinations) before choosing a framework version.

## Use the library directly

With `catnap-jackson3` on the classpath:

```java
import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import tools.jackson.databind.json.JsonMapper;

public class ProjectionExample {
    public static void main(String[] args) {
        var json = new Jackson3Json(JsonMapper.builder().build());
        var document = json.parse("""
            {"name":"ACME","price":{"amount":12.50,"currency":"USD"}}
            """);
        var query = Catnap.compile("name,price(amount)", QueryDialect.GOOGLE);

        var selected = query.evaluate(document, json.adapter());
        System.out.println(json.write(selected));
    }
}
```

A compiled query is immutable and reusable across documents and threads. Evaluation preserves the input tree and creates independent mutable result containers. `QueryCompiler` adds an optional bounded cache. The core has no third-party runtime dependencies; JSON libraries and frameworks live in separate modules.

## Query syntax

Choose a dialect explicitly in the core API or framework configuration. HTTP adapters default to Google syntax and the `fields` parameter.

| Selection | Google | LinkedIn |
|---|---|---|
| Two fields | `id,name` | `id,name` |
| Nested fields | `price(amount,currency)` | `price:(amount,currency)` |
| Nested path | `price/amount` | `price:(amount)` |
| Fields in every array item | `items(id,name)` | `items:(id,name)` |
| ID in every map value | `byId/*/id` | `byId:($*:(id))` |
| Entire document | `*` | `$*` |
| Literal name containing a slash | `"a/b"` | `"a/b"` |

Missing fields are omitted. Arrays retain their order and item count; scalar values and nulls are preserved. These dialects are Catnap field-selection profiles with a [specified grammar and behavior](docs/QUERY_SPECIFICATION.md); predicates, aliases, array slicing, and recursive descent are outside that grammar.

For HTTP requests, an absent or ASCII-blank parameter bypasses projection. Repeated parameters and invalid queries return HTTP 400. Query length, depth, selector count, evaluation work, and payload sizes have configurable limits.

## Streaming

Each framework integration provides explicit helpers for NDJSON and JSON-bearing SSE. They compile the query once per connection and project one bounded payload at a time. SSE IDs, event names, retry values, comments, and text/control events stay outside JSON projection. The adapters propagate cancellation and terminate failed streams without returning an unprojected fallback.

Use the documented helpers for streaming endpoints; installing a finite-response adapter does not automatically project arbitrary stream writes. See [Spring](docs/SPRING_INTEGRATION.md), [Micronaut](docs/MICRONAUT_INTEGRATION.md), [Quarkus](docs/QUARKUS_INTEGRATION.md), or [Ktor](docs/KTOR_INTEGRATION.md) for serialization context, backpressure, and error handling. For blocking standalone processing, `catnap-streams` supplies bounded NDJSON readers and writers.

## Building from source

Use the checked-in Gradle wrapper, or `gradlew.bat` on Windows. Java 17+ can launch it; the [daemon configuration](gradle/gradle-daemon-jvm.properties) selects Java 25 because the Micronaut build plugin requires it. Compilation and test toolchains are separate: the core targets Java 17 and compatibility tests use Java 17, 21, and 25.

```bash
./gradlew build compatibilityTest
```

Gradle can provision missing JDKs on the first run. For offline builds, install the required JDKs and make them discoverable through `org.gradle.java.installations.paths` in your private Gradle user properties. If the Micronaut plugin reports a Java version mismatch, check `./gradlew --version` and ensure you are using the wrapper and checked-in daemon criteria.

| Command | Purpose |
|---|---|
| `./gradlew check` | Formatting, unit/HTTP tests, bytecode and dependency checks |
| `./gradlew compatibilityTest` | Core tests on Java 17, 21, and 25 |
| `./gradlew streamingTest` | Framework HTTP suites, including NDJSON/SSE and lifecycle contracts |
| `./gradlew publicationSmokeTest` | Publish locally and test independent Gradle metadata and Maven POM consumers |
| `./gradlew :benchmarks:streaming-runner:smoke` | Exercise the five HTTP measurement servers and their runner |
| `./gradlew spotlessApply` | Apply Java, Kotlin, and Gradle script formatting |

Timed benchmarks and native-image builds are separate jobs. See [benchmarks](benchmarks/README.md), [measured performance and operating bounds](docs/PERFORMANCE.md), and [native-image verification](docs/NATIVE_IMAGES.md).

## Repository layout

| Location | Contents |
|---|---|
| `catnap-core` | Query parser, compiler, evaluator, tree interface, and bounded query cache |
| `catnap-jackson2`, `catnap-jackson3`, `catnap-kotlinx-json` | Native JSON tree bindings |
| `catnap-http`, `catnap-streams` | Shared HTTP projection services and NDJSON utilities |
| Framework modules | Spring adapters/starters, Micronaut adapter, Quarkus runtime/deployment, and Ktor plugin |
| `catnap-bom`, [testkit](catnap-testkit/README.md) | Dependency alignment and reusable conformance/HTTP contracts |
| [examples](examples/README.md) | Runnable applications and integration tests |
| [build-logic](build-logic/README.md) | Shared Gradle convention plugins |
| [docs](docs/README.md), [benchmarks](benchmarks/README.md) | Guides, verification records, and measurement tools |

## Contributing

Include a reproducible query, input JSON, expected result, and actual result when reporting an issue. For integration issues, include the framework, serializer, JDK version, and whether the response is finite JSON, NDJSON, or SSE.

Run the affected module's checks while developing, then `./gradlew build compatibilityTest` before submitting a change. Changes to publication metadata or dependency boundaries should also pass `publicationSmokeTest`. Add shared cases to the [conformance corpus](catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) when changing query behavior. See [extending Catnap](docs/EXTENDING.md) for new JSON bindings or framework adapters.

Dependency versions live in [the version catalog](gradle/libs.versions.toml); resolved dependencies are locked per project. After an intentional dependency update, run `./gradlew lockDependencies --write-locks` and review the lockfile changes. Build tooling has its own [lockfile workflow](build-logic/README.md#updating-build-tooling).

## License

Catnap is licensed under the [Apache License 2.0](LICENSE.txt).
