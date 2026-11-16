# Catnap documentation

Guides for using, configuring, extending, and verifying Catnap. New to the project? Start with the [project overview and quick start](../README.md) or the [runnable examples](../examples/README.md).

## Use Catnap

| Guide | What it covers |
|---|---|
| [Query specification](QUERY_SPECIFICATION.md) | Google and LinkedIn syntax, field-selection semantics, HTTP binding, and diagnostics |
| [Core API](CORE_API.md) | Compile and evaluate queries, implement a tree adapter, set limits, and reuse cached plans |
| [JSON integrations](JSON_INTEGRATIONS.md) | Jackson 2, Jackson 3, Kotlin serialization, and standalone NDJSON |
| [Spring integration](SPRING_INTEGRATION.md) | MVC/WebFlux starters, configuration, finite responses, NDJSON, and SSE |
| [Micronaut integration](MICRONAUT_INTEGRATION.md) | Native serialization, finite and streaming responses, and Java 25 requirements |
| [Quarkus integration](QUARKUS_INTEGRATION.md) | Runtime/deployment extension, streaming helpers, and finite JSON-array publishers |
| [Ktor integration](KTOR_INTEGRATION.md) | Plugin setup, response headers, Flow streaming, and imperative SSE |
| [Compatibility](COMPATIBILITY.md) | Tested JDK, framework, serializer, and native-image combinations |
| [Migration from Catnap 2](MIGRATION.md) | API changes, dependency choices, and unsupported legacy features |

## Develop and distribute

| Guide | What it covers |
|---|---|
| [Contributing](../README.md#contributing) | Reproductions, development checks, and dependency updates |
| [Extending Catnap](EXTENDING.md) | Add a JSON binding or framework integration and verify its contracts |
| [Testkit](../catnap-testkit/README.md) | Shared fixtures and HTTP/streaming contract helpers |
| [Conformance corpus](../catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) | Fixture schemas, comparison rules, and expected diagnostics |
| [Build conventions](../build-logic/README.md) | Gradle plugin structure, formatting, toolchains, and dependency locks |
| [Publication](PUBLICATION.md) | BOM, source/API artifacts, local consumers, signing, and GitHub Packages |

## Performance and verification

The current version is `3.0.0-SNAPSHOT`. The records below describe local verification of particular source snapshots, environments, and workloads. They do not establish a public release or a hosted CI result.

| Guide or record | What it establishes |
|---|---|
| [Performance and operating bounds](PERFORMANCE.md) | Projection costs, resource limits, measurements, and their scope |
| [Benchmark instructions](../benchmarks/README.md) | Reproduce JMH and real HTTP measurements |
| [Native-image verification](NATIVE_IMAGES.md) | Run Quarkus and Micronaut native checks |
| [Release audit](RELEASE_AUDIT.md) | Requirement coverage, recorded results, and known limitations |
| [Final local verification](verification/2026-11-16/release-candidate/README.md) | Build, compatibility, publication consumer, and streaming smoke results |
| [Java 17 wrapper launch](verification/2026-11-16/java17-launch/README.md) | Java 25 daemon selection from a Java 17 launcher |
| [JMH baseline](benchmarks/2026-11-16/README.md) | Raw CPU/allocation measurements and environment |
| [HTTP measurements](benchmarks/2026-11-16/http/README.md) | Throughput, latency, allocation, retention, and preserved failed attempts |
| [Native execution records](native/2026-11-16/README.md) | Compiler details, test reports, executable hashes, and source identity |

The [WebFlux drain investigation](benchmarks/2026-11-16/http/webflux-drain-audit/README.md) preserves a historical timeout whose cause remains unknown. The [Micronaut streaming investigation](benchmarks/2026-11-16/http/micronaut-reentrancy/README.md) records the bounded handoff fix and its measured cost.

## Design history

The [implementation plan](../IMPLEMENTATION_PLAN.md) and [serialization feasibility report](PHASE_1.md) explain how the project reached its current architecture. For current APIs and supported behavior, use the guides above. Dated benchmark and verification directories preserve the context of their original runs.
