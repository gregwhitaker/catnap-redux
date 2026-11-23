# Native verification — November 16, 2026

Both native suites passed on macOS 15.7.4/aarch64 using Oracle GraalVM 25.0.4+7.1 and Apple Clang 17.0.0. The native compiler used a 6 GiB heap, four compiler threads, and quick-build optimization. These are correctness checks, not performance measurements or Linux verification.

| Framework | Execution boundary | Tests | Failures | Skipped | Evidence |
|---|---|---:|---:|---:|---|
| Quarkus 3.40.1.2 | JVM HTTP client tests against the built native server | 62 | 0 | 0 | [Build and test log](quarkus/gradle.log), six JUnit XML reports in `quarkus/` |
| Micronaut platform 5.2.6 | Application and JUnit HTTP tests compiled into one native executable | 55 | 0 | 0 | [Build and test log](micronaut/gradle.log), [JUnit XML](micronaut/TEST-junit-jupiter.xml) |

The [summary](summary.json) records the installed compiler/runtime, download checksum, executable hashes, and per-file source hashes. The [source snapshot](source-snapshot.zip) preserves the repository's source, build declarations, runtime resources, and dependency locks at verification. It is a source snapshot, not a distribution of the generated executables or downloaded dependencies. Later test-only formatting or additional release checks do not change the recorded snapshot.

The suites cover finite projection, both query dialects, configured serializers and views, NDJSON and SSE wire behavior, early delivery, slow consumers, disconnect cancellation, failure timing, limits, and disablement. Quarkus additionally runs its finite JSON-array publisher contract against the external process. The [native guide](../../../website/content/NATIVE_IMAGES.md) provides commands and explains application-model reachability metadata and the cross-process source controls.

The first Quarkus attempt exposed missing reflection registration in the isolated serialization probe and a test fixture that assumed a shared JVM. The final run registers the probe's DTOs and reuses the existing lifecycle assertions through HTTP source controls. No production adapter behavior was changed for these corrections. These native checks are separate from the [HTTP measurements and recorded investigations](../../benchmarks/2026-11-16/http/README.md); their combined local verification scope is recorded in the [requirement audit](../../../website/content/RELEASE_AUDIT.md).

The later [Micronaut handoff verification](micronaut-handoff/summary.json) rebuilds the executable after the bounded asynchronous streaming change and again passes all 55 tests. Its [log](micronaut-handoff/gradle.log), [JUnit report](micronaut-handoff/TEST-junit-jupiter.xml), and source snapshot are separate from the initial baseline.
