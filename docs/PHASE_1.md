# Phase 1: build foundation and serialization feasibility

This is a historical Phase 1 report. For the implemented adapters and current verification status, see [the README](../README.md) and [requirement audit](RELEASE_AUDIT.md).

The first release includes finite JSON, NDJSON, and JSON-bearing SSE. Phase 1 establishes the build and proves an explicit path to project complete payloads before each framework encodes them. At this phase, the production compiler, evaluator, automatic adapter registration, and streaming lifecycle guarantees were deferred to later implementation phases; their completed verification is recorded in the [requirement audit](RELEASE_AUDIT.md).

## Pinned build matrix

The Gradle wrapper pins 9.7.0 and verifies its distribution SHA-256. Run the build with Java 25; toolchains keep the core and general-purpose Java code at Java 17. The version catalog, framework BOMs, and project lockfiles record the exact dependency graph.

| Component | Pinned version | Compilation and HTTP test runtime |
|---|---|---|
| Core and test helpers | Java 17 bytecode | Core tested separately on Java 17, 21, and 25 |
| Spring Boot MVC and WebFlux | Boot/plugin 4.1.2; Jackson databind 3.1.7 | Java 17 |
| Micronaut | Platform 5.2.6; core 5.2.23; application plugin 5.0.2; Serde 3.2.6; Jackson core 3.2.3 | Java 25 |
| Quarkus REST | Platform/plugin 3.40.1.2; Jackson databind 2.21.8 | Java 17 |
| Ktor | 3.6.1; Kotlin JVM/serialization plugins 2.4.21; kotlinx serialization 1.11.0 | Java 17 |
| Tests | JUnit 6.1.3 for core, Micronaut, Quarkus, and Ktor; Boot-managed 6.0.3 for Spring | Per module above |
| Formatting | Spotless 8.9.0; Google Java Format 1.33.0; ktfmt 0.60 | Build JVM |

Gradle's embedded Kotlin version for the included build remains Gradle-managed. It is separate from the Kotlin plugin used to build Ktor application code.

The framework BOMs remain scoped to their examples. The core has no third-party runtime dependencies, enforced by `:catnap-core:verifyCoreDependencies`. Every module checks its generated production class versions with `verifyBytecode`.

## Integration paths exercised

| Framework | Finite JSON | NDJSON | JSON-bearing SSE |
|---|---|---|---|
| Spring MVC | Explicit projection helper using the configured Jackson 3 mapper; return its native tree to the JSON converter | `StreamingResponseBody` writes one projected record and newline, with an explicit response content type | `sendProjected` wraps `SseEmitter.send` and carries request context into the asynchronous callback |
| Spring WebFlux | The same Jackson 3 boundary and native-tree response | Deferred `Flux` emits a projected native tree to the framework NDJSON encoder | Deferred `Flux<ServerSentEvent<JsonNode>>` preserves the event envelope |
| Micronaut | Injected `JsonMapper`, declared `Argument<Envelope<Quote>>`, and `writeValueToTree` | Deferred publisher writes a compact, projected JSON record plus newline | Native `Event<JsonNode>` delegates framing to Micronaut |
| Quarkus | Injected, customized Jackson 2 mapper with a typed writer; native-tree response | `Multi<JsonNode>` with NDJSON media type and JSON element type | `Multi<OutboundSseEvent>` uses the native SSE builder with projected JSON data |
| Ktor | A `ContentConverter` receives `ProbeValue<Envelope<Quote>>` and its `TypeInfo` before producing JSON | An explicit typed writer projects before writing a record and flushing | Typed SSE serialization callback receives `TypeInfo` and the complete pending value |

The Spring, Micronaut, and Quarkus helpers explicitly supply declared generic type and active serialization view to the application mapper. Ktor carries the generic type through the framework's converter/SSE callback and uses a contextual serializer. These are opt-in boundary probes, not evidence that an ordinary whole-response interceptor automatically captures every streaming send. Production adapters must retain these inputs when selecting their final interception mechanism.

Each probe serializes a complete `Envelope<Quote>` before selecting one field. The shared assertion checks that all eligible fields are present at that boundary, that an ignored property is absent, and that the active public/internal view has been applied. The HTTP contract then checks renamed fields, a custom money representation, and the projected output. Ktor's custom serializer implements equivalent public/internal behavior explicitly.

Request state is an immutable value captured by each publisher, callback, or pending send. Reactive probes defer projection until subscription; the MVC SSE probe executes on an asynchronous callback. Concurrent NDJSON requests with different fields and request IDs test isolation. No probe relies on a request thread's `ThreadLocal`.

The Jackson probes currently serialize through bytes to retain writer type and view behavior before reading the tree. Phase 4 will evaluate native tree/token-buffer paths without losing that context. Micronaut and Kotlin serialization already use native tree conversion.

## Probe contract and deliberate limits

The three accepted selections are `payload(display_name)`, `payload(amount)`, and `payload(internal)`. `ProbeRequest` recognizes these literal strings; it does not implement either full query grammar. The core currently contains the `QueryDialect` scaffold only.

Every example exposes `/probe/json`, `/probe/ndjson`, and `/probe/sse`. The shared `HttpProbe` starts from real HTTP responses and verifies:

- Finite projected JSON, public/internal view behavior, and an empty projected object.
- A single compact NDJSON record with its newline and correct content type.
- A single SSE JSON payload with preserved ID, event name, retry interval, comment, and event terminator.
- Concurrent NDJSON request isolation.

Each stream emits one item and completes. These tests establish serialization feasibility, not early delivery from a long-lived source, bounded buffering under slow consumers, cancellation cleanup, reconnection/replay, raw NDJSON framing, or post-commit failure handling. Those remain explicit Phase 6 release requirements. The example endpoints are not production adapters.

## Reproduction and CI

Install JDKs 17, 21, and 25, then run Gradle with Java 25:

```bash
./gradlew build compatibilityTest
./gradlew streamingTest
./gradlew :catnap-core:publishMavenJavaPublicationToLocalVerificationRepository
```

`build` packages every example and runs formatting, bytecode checks, dependency-boundary checks, and HTTP tests. `compatibilityTest` runs the core test in three actual JVMs. `streamingTest` selects the same framework test tasks without duplicating their implementation. Local publication exercises the publication convention; independent-consumer and BOM publication tests remain Phase 7 work.

The initial [GitHub Actions workflow](../.github/workflows/ci.yml) installs all three JDKs and runs `build compatibilityTest`. It pins action commits, validates the wrapper through Gradle's setup action, and retains test reports. Toolchain discovery explicitly uses the [environment variables supplied by setup-java](https://github.com/actions/setup-java/blob/v4/README.md#install-multiple-jdks), through [Gradle's environment-based toolchain configuration](https://docs.gradle.org/9.7.0/userguide/toolchains.html#sec:custom_loc). A hosted Actions run has not been performed locally.

Update dependency locks deliberately when changing versions:

```bash
./gradlew lockDependencies --write-locks
./gradlew -p build-logic lockDependencies --write-locks
```

Downloaded local toolchains, Gradle caches, and generated reports are ignored. No machine-specific paths are needed in tracked build configuration.

## Verification record

Locally verified on 2026-11-15 on macOS arm64:

- `./gradlew build compatibilityTest :catnap-core:publishMavenJavaPublicationToLocalVerificationRepository` passed with dependency locks enabled. Every example packaged successfully; formatting, bytecode checks, and the core dependency-boundary check passed.
- All five framework HTTP probe suites passed. Core runtime checks passed on Java 17.0.9, 21.0.13, and 25.0.5. Including the ordinary core test, the reports contain nine successful test executions and no failures or skipped tests.
- The locally published core JAR contains Java 17 class files (major version 61). Its POM contains the Apache 2.0 license and no dependency entries; Gradle metadata advertises Java 17 with no runtime dependencies. Source and Javadoc JARs were produced.
- Project and included-build dependency lock generation passed. The CI workflow parses as YAML; hosted execution remains unverified.

The pinned build reports Gradle 10 deprecation warnings from its current tooling. Gradle 9.7.0 is the verified wrapper version; Gradle 10 compatibility is not claimed.
