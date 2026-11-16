# Final local verification — November 16, 2026

The [Gradle log](gradle.log) records a successful final verification in 1 minute 57 seconds: 525 tasks, 124 executed and 401 up-to-date.

```bash
./gradlew spotlessCheck build compatibilityTest publicationSmokeTest \
  :benchmarks:streaming-runner:smoke --console=plain
```

The [summary](summary.json) records current passing reports: 1,152 main/example/benchmark tests; 188 core tests on each of Java 17, 21, and 25; and 275 independent publication-consumer tests in each of Gradle-metadata and POM-only modes. There are no failures, errors, or skipped tests in those reports. Unchanged tasks may be up-to-date; these totals do not claim every test re-executed in this invocation.

The local Maven repository contains all 16 libraries plus the BOM, including their metadata and source/API documentation artifacts where applicable. The independent consumer builds verify their contents and runtime dependencies. The [streaming smoke summary](smoke/summary.json) and raw results cover all five framework targets. Smoke timings validate the harness and are not performance measurements.

All six [build-logic projects](../../../../build-logic/README.md) also pass their [aggregate build](build-logic.log). Each convention plugin owns its build script, implementation, and lockfile; the root only aggregates lifecycle tasks.

[Source hashes](summary.json) and the [source archive](sources.zip) identify the verified code, build declarations, and locks. The summary compares them with the existing Quarkus and refreshed Micronaut native evidence: no changed source used by those native suites remains unverified. Their [native results](../../../native/2026-11-16/README.md), [performance measurements](../../../benchmarks/2026-11-16/README.md), and [requirement audit](../../../RELEASE_AUDIT.md) are separate artifacts. Public release upload and hosted CI were not run.
