# Compatibility and verification scope

This matrix describes the framework, serializer, and Java combinations used by the Catnap 3 source checkout. For dependency setup and artifact availability, see [installation](INSTALLATION.md). The [release checklist](RELEASING.md) explains how to verify a candidate and retain its results in CI artifacts.

## Java and build tooling

| Component | Requirement | Verification |
|---|---|---|
| Gradle build JVM | Java 25; wrapper launcher may use Java 17+ | Checked-in daemon JVM criteria, wrapper 9.7.0 with pinned distribution checksum, Kotlin plugin 2.4.21 |
| Core and ordinary Java/Kotlin libraries | Java 17 | Production class-file checks; core tests on Java 17, 21, and 25 |
| Micronaut adapter and example | Java 25 | Separate module toolchain and bytecode baseline; real HTTP and published-consumer tests on Java 25 |
| Kotlin integration code | JVM target 17 | Kotlin toolchain/target checks, binding tests, Ktor HTTP tests |
| Streaming measurement processes | Java 25 | Separate client/server JVMs; this measurement choice does not raise the other libraries' minimum runtime |

The core compatibility tasks exercise Java 17, 21, and 25. Consult the results for a particular run to identify its exact JDK patch versions. The Gradle 9.7.0 build still reports deprecations from its pinned tooling; Gradle 10 compatibility is not claimed.

The main framework test suites use their module toolchains: Java 17 for MVC, WebFlux, Quarkus, and Ktor, and Java 25 for Micronaut. The separate Java 25 streaming jobs exercise the stated streaming workload; they do not substitute for running each framework's entire test suite on every supported JDK.

The [daemon JVM criteria](https://github.com/gregwhitaker/catnap-redux/blob/main/gradle/gradle-daemon-jvm.properties) select Java 25 before Gradle loads framework plugins. Project compilation toolchains alone cannot satisfy a plugin's build-JVM requirement. The generated criteria include JDK download URLs; the pinned Foojay settings plugin also resolves missing compilation/test toolchains. Independent publication consumers receive the same daemon criteria. Offline users must provide discoverable installations. The shell's `JAVA_HOME` does not need to change.

## Implemented framework and serializer combinations

These are the combinations resolved in the example lockfiles and exercised by the current build. They are a tested matrix, not an assertion that arbitrary framework or serializer version combinations are interchangeable.

| Integration | Framework/runtime baseline | JSON boundary | Finite JSON | NDJSON and JSON-bearing SSE |
|---|---|---|---|---|
| Standalone Jackson 2 | No framework | Jackson Databind 2.21.8 | Yes | Bounded standalone NDJSON utilities; SSE framing belongs to a transport adapter |
| Standalone Jackson 3 | No framework | Jackson Databind 3.1.7 | Yes | Bounded standalone NDJSON utilities; SSE framing belongs to a transport adapter |
| Kotlin serialization | No framework | kotlinx.serialization JSON 1.11.0 | Yes | Per-payload binding used by Ktor; standalone framing is separate |
| Spring MVC / Boot | Boot 4.1.2, Spring Framework 7.0.10 | Configured Jackson 3.1.7 writer/converter | Yes | Explicit bounded emitter helpers, with native SSE framing |
| Spring WebFlux / Boot | Boot 4.1.2, Spring Framework 7.0.10, Reactor 3.8.8 | Configured Jackson 3.1.7 writer/encoder | Yes | Reactive response helpers, with native flushing and SSE framing |
| Micronaut | Platform 5.2.6, core 5.2.23 | Configured native `JsonMapper` and Serde Jackson 3.2.6; its underlying Jackson Core is 3.2.3 | Yes | Native response helpers preserving `Argument`/serializer context |
| Quarkus | 3.40.1.2 | Configured Jackson 2.21.8 writer and native providers/interceptors | Yes | Explicit `CatnapStream` resources and bounded finite JSON-array publishers |
| Ktor | 3.6.1 | Configured kotlinx.serialization JSON 1.11.0 and declared strategy/type | Yes | Coroutine/Flow and imperative SSE helpers |

Spring Boot 3/Jackson 2 integration is not implemented by the Boot 4 starters. The standalone Jackson 2 binding and Quarkus integration do not imply a Boot 3 adapter. Other JSON trees can implement the framework-independent `JsonTreeAdapter<N>` contract; see [adding an integration](EXTENDING.md).

Streaming support uses the documented explicit helper APIs. Installing a finite-response adapter does not automatically intercept every arbitrary writer, emitter, publisher, byte stream, or native send call. See [Spring](SPRING_INTEGRATION.md), [Micronaut](MICRONAUT_INTEGRATION.md), [Quarkus](QUARKUS_INTEGRATION.md), and [Ktor](KTOR_INTEGRATION.md) for the supported boundaries and their cancellation/error policies.

## Dependency and publication boundaries

The core has no third-party runtime dependencies. Its API exposes neither Jackson nor HTTP/framework/reactive types. `catnap-http` and `catnap-streams` hold shared JVM behavior; framework dependencies stay in their integrations.

The MVC starter does not acquire Reactor through Catnap. MVC and WebFlux have separate starters. Quarkus publishes separate runtime and deployment artifacts; application runtime dependencies exclude augmentation code. Micronaut's Java 25 requirement does not affect the Java 17 core. The Catnap BOM aligns the 16 published libraries and imports no framework BOM. Each integration declares its own framework requirements.

Independent consumer builds verify both Gradle module metadata and Maven POM consumption, without project substitution. They also inspect runtime dependencies, bytecode baselines, source/API documentation jars, framework registration metadata, and BOM constraints. See [publication verification](PUBLICATION.md) for commands and evidence. Examples, measurement servers, and build conventions are not published as production libraries.

## Query and wire compatibility

Google and LinkedIn implement the documented [version 1 field-selection profiles](QUERY_SPECIFICATION.md). CATNAP adds a bounded RSQL predicate subset with typed JSON comparisons and ordered array filtering. Without predicates, all three provide equivalent selection semantics through one immutable AST; they do not implement every vendor-specific Google or LinkedIn API feature. HTTP integrations default to `CATNAP` and the `fields` parameter; `GOOGLE`, `LINKEDIN`, and a different parameter name remain configurable. Core compilation always requires an explicit dialect. Legacy single-`=` predicates require migration to CATNAP `==` syntax; JSONP remains unsupported. See [migration from Catnap 2](MIGRATION.md).

Finite responses materialize one document. NDJSON and SSE materialize one logical record or JSON payload at a time, preserve record/event count, and retain the framework's transport lifecycle. Source trees are preserved; mutable result containers are isolated. SSE replay storage and reconnection policy belong to the application.

The shared wire/lifecycle suites cover early delivery, configured dialects and serialization, metadata, slow consumers, disconnects, and failures before/after commitment. Default CATNAP predicates and explicit dialect overrides are tested across all five frameworks, with configured-serializer finite HTTP tests and array-filtering NDJSON tests in MVC. See [the performance guide](PERFORMANCE.md) for sustained HTTP measurement guidance.

## Native images and hosted CI

The [native-image suites](NATIVE_IMAGES.md) exercise Quarkus HTTP contracts against an external native server and compile Micronaut's tests with its application into a native executable. Both include the CATNAP default, array predicates, and catalog cases. Application DTOs and custom serializers still need their framework's reachability metadata. Check the source revision and environment in the corresponding run before making a native-support claim. These suites do not establish native-image execution for Spring MVC, WebFlux, Ktor, or untested operating systems.

The [Build workflow](https://github.com/gregwhitaker/catnap-redux/blob/main/.github/workflows/ci.yml) runs the full build, core compatibility matrix, independent local publication consumers, and five-framework streaming smoke suite on Ubuntu 24.04. Native execution and timed benchmarks are separate manual jobs. Reports and measurements are uploaded as CI artifacts; a passing JVM job does not establish native execution or performance. [Remote publication checks](PUBLICATION.md#publish-to-github-packages) additionally verify consumption from GitHub Packages.
