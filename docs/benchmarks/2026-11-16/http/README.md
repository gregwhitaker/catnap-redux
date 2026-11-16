# HTTP streaming measurements — November 16, 2026

All five framework targets have completed all 12 cases in the preserved runs below. Micronaut has a bounded asynchronous handoff, with its native, stress, and full-duration checks repeated successfully. An earlier WebFlux drain timeout remains an unexplained historical observation. The [investigation](webflux-drain-audit/README.md) preserves the original failure, additional diagnostics, and a passing 24-case replay from the archived MVC/WebFlux sources. No WebFlux production fix or hard real-time guarantee is claimed.

The [methodology and commands](../../../../benchmarks/README.md) describe the shared 1,024-byte payload, two connections, asynchronous producer batches, native/bypass/full/sparse paths, JVM flags, and measurement bounds. Environment: Corretto 25.0.5+10-LTS, macOS 15.7.4/aarch64, ten available processors. No other builds ran during the timed windows. Each throughput result is one five-second delivery window after two seconds of warmup, not a confidence interval. First-item latency uses 30 fresh requests against a warm server and query cache. These local observations do not rank frameworks or establish production latency guarantees.

## Recorded attempts

- [Attempt 1 summary and source hashes](attempt-1/summary.json): MVC completed; WebFlux stopped during the sparse NDJSON warmup because the client did not satisfy the exact emitted/delivered-count assertion within 15 seconds. Its error reported 356,373 expected records; that first harness version did not preserve the actual received count. [Gradle log](attempt-1/gradle.log), [MVC raw results](attempt-1/mvc.json), [partial WebFlux results](attempt-1/webflux.json), and [measured source archive](attempt-1/measured-sources.zip) are preserved.
- [Attempt 2 summary and source hashes](attempt-2/summary.json): a focused WebFlux run completed after adding exact delivered/per-connection/server counts to failure diagnostics. This change affects the failure-reporting path; the measurement paths and adapters are unchanged. A successful rerun does not establish the cause of the earlier timeout. [Gradle log](attempt-2/gradle.log), [WebFlux raw results](attempt-2/webflux.json), and [measured source archive](attempt-2/measured-sources.zip) are preserved.

- [Attempt 3 summary and source hashes](attempt-3/summary.json): Micronaut, Quarkus, and Ktor completed all 36 cases, with exact emitted/delivered counts, 30 latency samples per path, and live-connection retained-memory checkpoints. [Gradle log](attempt-3/gradle.log), [Micronaut](attempt-3/micronaut.json), [Quarkus](attempt-3/quarkus.json), [Ktor](attempt-3/ktor.json), and [measured source archive](attempt-3/measured-sources.zip) are preserved. Its normal measurement path and production adapters match the earlier attempts; the runner additionally contains a separate stress mode.

- [Attempt 4 summary and source hashes](attempt-4-micronaut/summary.json): refreshed Micronaut measurements after adding the one-item asynchronous native-write handoff. All 12 cases pass. The Micronaut rows below use this run; the previous rows remain recoverable from attempt 3. [Gradle log](attempt-4-micronaut/gradle.log), [raw results](attempt-4-micronaut/micronaut.json), and [source archive](attempt-4-micronaut/measured-sources.zip) are preserved.

## Follow-up drain diagnostics

The [short-boundary WebFlux run](stress-short-webflux/summary.json) passed 800 pause/drain/resume boundaries across all eight protocol/path combinations, delivering 19,863,037 records. The [reconnecting WebFlux run](stress-reconnect-webflux/summary.json) passed 30 two-second sparse NDJSON windows after 900 first-item connections, delivering 14,542,425 records. Both preserve source archives and server/client results. They did not reproduce the original drain timeout and do not establish its cause. No adapter fix is claimed from these passing reruns; the subsequent [investigation assessment](webflux-drain-audit/README.md) records the evidence and its limits.

## Completed delivery windows

`all` selects every field and preserves payload size. `sparse` selects a 44-byte JSON result. Body bytes include NDJSON/SSE framing and exclude HTTP headers/chunk framing. Allocation is server-wide and includes control-request overhead. The client CPU counter and individual latency samples remain in the raw files.

| Framework | Protocol | Path | Records/s | Body MiB/s | Allocated KiB/item | First-item p95 ms |
|---|---|---|---:|---:|---:|---:|
| mvc | ndjson | native | 54,873 | 53.64 | 1.85 | 3.319 |
| mvc | ndjson | bypass | 58,446 | 57.13 | 13.38 | 1.368 |
| mvc | ndjson | all | 52,754 | 51.57 | 27.67 | 1.280 |
| mvc | ndjson | sparse | 56,130 | 2.41 | 23.92 | 1.093 |
| mvc | sse | native | 57,948 | 59.19 | 2.66 | 1.038 |
| mvc | sse | bypass | 56,441 | 57.65 | 15.53 | 1.086 |
| mvc | sse | all | 50,590 | 51.67 | 30.04 | 1.716 |
| mvc | sse | sparse | 53,119 | 4.61 | 27.02 | 1.084 |
| webflux | ndjson | native | 204,371 | 199.78 | 2.33 | 1.805 |
| webflux | ndjson | bypass | 203,441 | 198.87 | 15.52 | 1.465 |
| webflux | ndjson | all | 201,842 | 197.30 | 29.86 | 1.507 |
| webflux | ndjson | sparse | 237,660 | 10.20 | 25.04 | 1.708 |
| webflux | sse | native | 184,708 | 188.66 | 5.00 | 1.635 |
| webflux | sse | bypass | 177,996 | 181.80 | 20.59 | 1.685 |
| webflux | sse | all | 181,888 | 185.78 | 35.11 | 1.783 |
| webflux | sse | sparse | 236,492 | 20.52 | 27.15 | 1.835 |
| micronaut | ndjson | native | 202,231 | 197.68 | 6.56 | 1.493 |
| micronaut | ndjson | bypass | 58,418 | 57.10 | 16.51 | 1.161 |
| micronaut | ndjson | all | 52,531 | 51.35 | 34.29 | 1.232 |
| micronaut | ndjson | sparse | 56,158 | 2.41 | 27.44 | 1.136 |
| micronaut | sse | native | 182,808 | 187.59 | 6.73 | 1.564 |
| micronaut | sse | bypass | 54,559 | 55.99 | 17.12 | 1.509 |
| micronaut | sse | all | 49,671 | 50.97 | 35.17 | 1.746 |
| micronaut | sse | sparse | 53,163 | 4.87 | 28.98 | 0.809 |
| quarkus | ndjson | native | 58,535 | 57.22 | 9.13 | 1.565 |
| quarkus | ndjson | bypass | 53,883 | 52.67 | 17.99 | 1.521 |
| quarkus | ndjson | all | 50,107 | 48.98 | 32.24 | 1.617 |
| quarkus | ndjson | sparse | 53,710 | 2.30 | 25.52 | 1.211 |
| quarkus | sse | native | 54,861 | 57.60 | 13.77 | 0.741 |
| quarkus | sse | bypass | 53,656 | 54.80 | 21.84 | 0.629 |
| quarkus | sse | all | 48,979 | 50.03 | 36.23 | 0.690 |
| quarkus | sse | sparse | 50,389 | 4.37 | 27.34 | 0.575 |
| ktor | ndjson | native | 51,562 | 50.40 | 5.04 | 1.106 |
| ktor | ndjson | bypass | 50,440 | 49.31 | 13.91 | 1.121 |
| ktor | ndjson | all | 37,465 | 36.62 | 26.35 | 1.268 |
| ktor | ndjson | sparse | 44,322 | 1.90 | 20.56 | 1.020 |
| ktor | sse | native | 33,121 | 34.18 | 13.36 | 1.636 |
| ktor | sse | bypass | 43,978 | 45.38 | 23.77 | 0.998 |
| ktor | sse | all | 34,869 | 35.98 | 37.34 | 1.125 |
| ktor | sse | sparse | 44,185 | 4.30 | 23.65 | 1.426 |

## Retained-memory samples

Both connections remained open across observed-GC samples after 5, 20, and 60 active seconds at a target of 500 items/second per connection. Values below are the maximum-minus-minimum ranges across those samples. They are not allocated bytes or proof of behavior beyond this workload. JVM and Netty direct-memory counters may overlap; do not add them.

| Framework | Protocol | Path | Records at final sample | Heap range bytes | JVM direct range bytes | Netty direct range bytes |
|---|---|---|---:|---:|---:|---:|
| mvc | ndjson | native | 57,278 | 136 | 0 | 0 |
| mvc | ndjson | all | 57,085 | 280 | 0 | 0 |
| mvc | sse | native | 56,670 | 840 | 0 | 0 |
| mvc | sse | all | 56,230 | 24 | 0 | 0 |
| webflux | ndjson | native | 60,038 | 56 | 0 | 0 |
| webflux | ndjson | all | 60,031 | 1,048,880 | 0 | 0 |
| webflux | sse | native | 60,022 | 1,049,160 | 0 | 0 |
| webflux | sse | all | 60,040 | 1,048,576 | 0 | 0 |
| micronaut | ndjson | native | 60,003 | 1,104,104 | 0 | 0 |
| micronaut | ndjson | all | 59,330 | 1,048,576 | 0 | 0 |
| micronaut | sse | native | 60,008 | 1,048,576 | 0 | 0 |
| micronaut | sse | all | 58,512 | 1,048,600 | 0 | 0 |
| quarkus | ndjson | native | 57,245 | 1,050,320 | 65,536 | 65,536 |
| quarkus | ndjson | all | 57,112 | 656 | 0 | 0 |
| quarkus | sse | native | 56,897 | 1,048,384 | 0 | 0 |
| quarkus | sse | all | 58,463 | 1,120 | 0 | 0 |
| ktor | ndjson | native | 58,721 | 123,704 | 0 | 0 |
| ktor | ndjson | all | 59,069 | 49,504 | 0 | 0 |
| ktor | sse | native | 58,426 | 247,440 | 0 | 0 |
| ktor | sse | all | 58,289 | 41,896 | 0 | 0 |

All listed retention ranges passed the configured maximum of the larger of 16 MiB or 10% of the minimum sample. The five-framework measurement matrix is complete for this workload. The [requirement audit](../../../RELEASE_AUDIT.md) records the current passing verification alongside the unexplained historical timeout; a passing retention result does not explain that timeout.

The later [Micronaut reentrancy investigation](micronaut-reentrancy/README.md) reproduces the synchronous-source/cleanup interaction and verifies the bounded adapter handoff across all six Catnap stream modes. Micronaut rows above use the refreshed full-duration run. WebFlux [tracing validation](stress-trace-webflux/summary.json) passes all eight protocol/path combinations across 80 boundaries, with per-connection source and native-writer byte counters independently matched to the client. This additional passing run does not explain the original timeout.

The one-item handoff has a material throughput cost with this asynchronous benchmark source: full-field NDJSON measured 52,531 records/s, compared with 181,826 in the earlier implementation; SSE measured 49,671 versus 172,146. These are separate local windows, not confidence intervals or a universal scheduler penalty. The new default keeps preparation bounded to one queued item and prevents the reproduced synchronous callback loop. All four refreshed retention cases passed; direct-memory counters were unchanged across their 5/20/60-second samples, and heap ranges were about 1 MiB.

The [traced reconnect run](stress-traced-reconnect-webflux/summary.json) passed 100 two-second sparse NDJSON windows after 3,000 first-item connections, delivering 48,005,331 records. Every boundary independently matched each connection’s source count and native-writer body bytes to the client, in addition to the global emitted/delivered count. The original failure was not reproduced; no WebFlux adapter fix is claimed. [Exact command](stress-traced-reconnect-webflux/command.json) and [log](stress-traced-reconnect-webflux/gradle.log) are preserved.

The [final drain investigation](webflux-drain-audit/README.md) adds held-reader tests with and without tracing, synchronous-source reconnects, and a replay of the archived attempt-1 sources. All pass, including the complete MVC workload followed by all WebFlux cases. The original timeout recorded an expected count but no delivered count, so it does not establish dropped or duplicated records. Its cause remains unknown, and the original failed run remains available.
