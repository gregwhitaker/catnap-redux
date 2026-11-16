# Catnap benchmarks

This directory contains JMH microbenchmarks for the projection pipeline and a real HTTP runner for NDJSON/SSE across all five framework adapters. Use it to measure the cost of a specific workload or investigate changes in throughput, allocation, latency, and retained memory.

Run commands from the repository root with the wrapper and [documented JDK setup](../README.md#building-from-source). Measurements use Java 25 by default. Performance jobs are separate from ordinary `check`; the main build compiles the benchmarks and validates their inputs/results without running timed measurements.

## Choose a measurement

| Task | Purpose | Output directory |
|---|---|---|
| `:benchmarks:jmhSmoke` | Briefly execute every microbenchmark and validate its setup | `benchmarks/build/results` |
| `:benchmarks:jmh` | Measure CPU time and allocation in forked JVMs | `benchmarks/build/results` |
| `:benchmarks:streaming-runner:smoke` | Validate real HTTP servers and measurement machinery | `benchmarks/streaming-runner/build/results/smoke` |
| `:benchmarks:streaming-runner:measure` | Measure streaming throughput, latency, allocation, and retention | `benchmarks/streaming-runner/build/results/measure` |
| `:benchmarks:streaming-runner:boundaryStress` | Investigate delivery at repeated pause/drain boundaries | `benchmarks/streaming-runner/build/results/stress` |

Smoke runs validate the machinery; use full runs for performance evidence. Results are replaced on subsequent invocations, so preserve a baseline before starting a follow-up measurement.

The [November 16, 2026 baseline](../docs/benchmarks/2026-11-16/README.md) records the first full JMH run. The [performance guide](../docs/PERFORMANCE.md) explains operating bounds and links the separate HTTP and retained-memory evidence. The [CI workflow](../.github/workflows/ci.yml) is configured to run the HTTP smoke suite during verification; its manual `benchmarks` and `streaming_benchmarks` inputs enable full measurements.

## JMH

```bash
# Validate benchmark setup and execute every case briefly; not a performance result
./gradlew :benchmarks:jmhSmoke

# Two forks, three 1-second warmups, five 1-second measurements, one worker, GC profiler
./gradlew :benchmarks:jmh

# Use a different installed JVM or focus a follow-up experiment
./gradlew :benchmarks:jmh -PbenchmarkJava=17 -PjmhInclude=StreamItemBenchmark
```

The benchmark sources target Java 17; the default measurement JVM is Java 25. Every fork uses `-Xms256m -Xmx512m`. JMH 1.37 and its annotation processor are pinned in the version catalog and lockfile. Generated benchmark harnesses consume every returned result to prevent dead-code elimination. Timed methods do not build fixture inputs or compile queries unless query compilation is the operation being measured.

Results are written to `build/results/jmh.json` and environment/workload/source-identity metadata to `build/results/environment.json` within this module. A focused run replaces these files; preserve a baseline before running a follow-up experiment. The smoke run writes `jmh-smoke.json` separately and has no warmup, so its timings must not be cited as release measurements.

| Benchmark | Operation measured |
|---|---|
| `QueryBenchmark.compile` | Uncached compilation for both dialects |
| `QueryBenchmark.cachedCompile` | A hot key in the default-capacity bounded cache |
| `PipelineBenchmark.parseTree` | Parse encoded UTF-8 JSON into a precise native Jackson 3 tree |
| `PipelineBenchmark.domainToTree` | Convert the configured writer's domain value through native tokens into a tree |
| `PipelineBenchmark.evaluateExistingTree` | Evaluate an already compiled query over an existing input tree |
| `PipelineBenchmark.serializeSelectedTree` | Serialize the already selected tree into UTF-8 bytes |
| `PipelineBenchmark.nativeSerializationBaseline` | Serialize the full domain value normally, without projection |
| `PipelineBenchmark.completeResponseProcessing` | Bounded native writer capture, parse, evaluate, and bounded selected-output encoding |
| `StreamItemBenchmark.nativeRecordEncoding` | Ordinary JSON serialization of one stream record |
| `StreamItemBenchmark.bypassRecordEncoding` | Bounded record preparation with projection inactive |
| `StreamItemBenchmark.projectedRecordEncoding` | Bounded serialization, full-field projection, and compact record preparation |

Full response processing excludes HTTP transport, request binding, and query compilation. Stream-item preparation excludes the framework's final NDJSON delimiter or SSE envelope. Real HTTP latency, throughput, and retained-memory jobs are separate; these microbenchmarks cannot establish those properties.

The pipeline cases cover a small object, a 4,096-element root array, 48 nested child objects, one selected member out of a 512-member object, and wildcard selections over 64 map entries. The environment file records exact input/selected sizes and queries. The stream-item cases use 1,024-byte and 65,536-byte string padding; JSON property names and delimiters add to those sizes. All three stream-item paths emit byte-identical full documents, so their relative costs are not explained by smaller projected output. Pipeline selections intentionally remove fields, and their output sizes must be considered when comparing the full-response result to native serialization.

`gc.alloc.rate.norm` measures allocated bytes per operation, not retained memory. JMH reports latency means and 99.9% confidence intervals from fork/iteration samples. Keep error bars and raw results when reporting numbers. A hot-cache benchmark does not model contention or a cache full of unique queries. A single local machine does not establish production latency guarantees or rank different frameworks.

Run timed measurements with other builds idle. Record the JVM, collector/heap flags, host resources, payload size, concurrency, and source identity. Re-run a focused case when uncertainty or a changed implementation warrants it. See the [release audit](../docs/RELEASE_AUDIT.md) and [native-image guide](../docs/NATIVE_IMAGES.md) for the broader verification scope.

## HTTP streaming measurements

```bash
# Validate all five real HTTP servers, both protocols, and the measurement machinery
./gradlew :benchmarks:streaming-runner:smoke

# Full run: native/bypass/full/sparse throughput and latency, plus open-stream retention
./gradlew :benchmarks:streaming-runner:measure

# Focus diagnostics on one or more servers
./gradlew :benchmarks:streaming-runner:smoke -PstreamFrameworks=micronaut,ktor

# Keep connections open across 100 exact emitted/delivered-count boundaries per path
./gradlew :benchmarks:streaming-runner:boundaryStress -PstreamFrameworks=webflux

# Exercise connection churn before each two-second sparse NDJSON delivery window
./gradlew :benchmarks:streaming-runner:boundaryStress -PstreamFrameworks=webflux \
  -PstreamStressCase=ndjson:sparse -PstreamStressSeconds=2 \
  -PstreamStressBoundaries=30 -PstreamStressReconnect=true
```

The runner launches the dedicated MVC, WebFlux, Micronaut, Quarkus, and Ktor servers sequentially in separate Java 25 processes with `-Xms256m -Xmx512m`. It uses HTTP/1.1 over loopback and two concurrent connections. Each framework receives the same immutable 1,024-byte JSON value from the same demand-aware publisher instance. Unlimited-rate production uses asynchronous batches of at most 32 emissions, yielding between batches so synchronous demand cannot monopolize a transport event loop. The four paths are ordinary native serialization, the Catnap streaming helper without a query, `fields=*`, and `fields=id,name,price(amount)`. The sparse output is 44 bytes; full-field comparisons preserve payload size. SSE events include an ID, event name, retry value, and comment.

MVC, WebFlux, and Quarkus use native typed streaming responses for the baseline. Micronaut has no typed `application/x-ndjson` writer in the pinned version, so its baseline serializes with the configured native mapper and appends LF to each record. Ktor likewise uses its configured serializer and flushes each newline-delimited record. All five use their native SSE facilities. Native Quarkus SSE includes a `content-type` extension field; the Catnap path emits the standard SSE envelope. Body-byte measurements include these real framing differences.

For each protocol/path, the full run warms two unlimited-rate connections for two seconds, measures a five-second delivery window, then records 30 first-item latency samples from fresh streaming requests on the warm server. Throughput includes client consumption and loopback transport; the client CPU counter helps identify client limitations. The runner pauses the producer and drains every emitted item at window boundaries. Server-wide allocation includes serialization, projection, framing, transport, and the small control-request cost. It is allocated bytes per delivered item, not retained memory. These are descriptive local windows, not confidence intervals or a cross-framework ranking.

Retention uses two connections paced at 500 items/second each. Both remain open across samples after 5, 20, and 60 active seconds. At each checkpoint the runner pauses production, drains all emitted items, requests GC, requires an observed collection, then records heap and JVM/Netty direct-memory counters. Direct-memory counters may overlap and must not be summed. Netty metrics identify whether its global direct-memory counter is available or only the pooled-allocator fallback can be read. A range above the larger of 16 MiB or 10% of the minimum sample fails the run. This is evidence for the stated payload, concurrency, and duration, not a proof for every application. The smoke task uses much shorter windows and only checks the machinery; its numbers are not performance evidence.

Every connection validates its first JSON payload and all subsequent constant payloads. SSE metadata is checked on every event. Each protocol also tests simultaneous `id` and `name` queries over the same publisher, verifies the surviving subscriber after one disconnects, and requires zero active subscriptions after cleanup. Fatal memory/stack errors in a server log also fail the run. The client retains one bounded record/event instead of accumulating the response.

`boundaryStress` is a diagnostic job, not a performance baseline. By default it runs all eight protocol/path combinations, with two connections kept open across 100 production/pause/drain/resume boundaries of 0.1 seconds each. `streamStressCase` accepts `all` or a protocol/path such as `ndjson:sparse`; `streamStressSeconds` sets the production window, and `streamStressBoundaries` sets its repetition count. With `streamStressReconnect=true`, each repetition first opens and closes 30 first-item requests, then creates a new measured connection pair. Every pause still requires an exact emitted/delivered count. Failure diagnostics preserve the server count and per-client counts; successful stress reruns alone do not explain an earlier failure. For synchronous-source diagnostics, `streamStressSynchronous=true` restores inline request/drain behavior. `streamStressResumeFirst=true` resumes a paused producer before closing the clients, and `streamStressUnpausedStart=true` starts connections while production is enabled. All three default to false. The [Micronaut investigation](../docs/benchmarks/2026-11-16/http/micronaut-reentrancy/README.md) uses these controls to isolate the original native direct-memory failure and verify the bounded adapter handoff. `streamStressTrace=true` enables WebFlux-only connection diagnostics: bounded source/native-writer counters are compared to the incremental client at every boundary, and a mismatch captures recent connection counters and bounded thread stacks. It is disabled for performance measurements. `streamStressHoldReaders=true` pauses incremental client reads during each production window, then releases them after the source is paused; the unchanged drain assertion must consume the queued records within 15 seconds. It defaults to false and records the observed emitted-minus-consumed backlog at each boundary. This measures backlog across the transport path, not an adapter-owned queue. The [WebFlux drain investigation](../docs/benchmarks/2026-11-16/http/webflux-drain-audit/README.md) preserves these runs and the archived-source replay. Stress evidence is written under `streaming-runner/build/results/stress` and must be preserved before another stress invocation.

Results are saved incrementally under `streaming-runner/build/results/{smoke,measure}` with per-framework JSON, server logs, and a completion summary. Full measurements also archive source files, build declarations, runtime resources, and dependency locks, with per-file SHA-256 hashes. Partial or failed runs contain no successful overall completion marker. Each invocation replaces its summary and selected-framework files; preserve the directory before rerunning. Files from a previous framework selection are not part of the current run unless its summary lists them as completed. [Recorded HTTP attempts](../docs/benchmarks/2026-11-16/http/README.md) illustrate separate source identities and failure preservation. Run measurements with other builds idle; these tasks are separate from ordinary `check`.
