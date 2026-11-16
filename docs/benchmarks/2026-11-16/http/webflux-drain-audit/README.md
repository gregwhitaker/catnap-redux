# WebFlux drain-timeout investigation

The original observation is an **unreproduced 15-second drain timeout with an undetermined cause**. No WebFlux production change was made during this investigation. All subsequent measured and diagnostic runs below pass the exact emitted/delivered-count checks. They do not establish the cause of the original timeout or prove that it cannot recur.

## What the failed attempt establishes

[Attempt 1](../attempt-1/summary.json) stopped during the sparse NDJSON warmup after completing the MVC workload and three WebFlux paths. Its [log](../attempt-1/gradle.log) recorded `Timed out: Client drain: expected 356373 delivered records`. The harness did not record the actual delivered count, per-connection counts, or native-writer counters. Consequently, the evidence cannot distinguish delayed delivery, a stalled reader/writer, count-accounting behavior, or missing/duplicate records. Earlier release-status wording called this a count mismatch; that description implied more diagnostic information than was recorded. The original run remains failed and its partial results remain archived.

The current harness retains the exact count requirement and 15-second drain deadline. Failure reporting now records expected/delivered/per-connection counts and server metrics. Optional WebFlux tracing also compares each connection's source count and native-writer bytes with the client and captures bounded thread snapshots on failure.

## Controlled follow-up runs

The earlier [800-boundary run](../stress-short-webflux/summary.json), [30 reconnect windows](../stress-reconnect-webflux/summary.json), [80 traced boundaries](../stress-trace-webflux/summary.json), and [100 traced reconnect windows](../stress-traced-reconnect-webflux/summary.json) all passed. The last delivered 48,005,331 records after 3,000 first-item connections, with independent source/native-writer/client checks at each boundary.

The additional runs preserved here test queued delivery, synchronous demand, and the original measurement sources:

| Run | Conditions | Result |
|---|---|---|
| [Held readers, tracing enabled](webflux-held-traced/summary.json) | Two connections, twenty two-second sparse NDJSON windows; pause client reads during production, pause the source, then release readers | 1,973,378 records; largest observed unread backlog 208,574 records; exact global/per-connection counts and native-writer bytes match |
| [Held readers, tracing disabled](webflux-held-untraced/summary.json) | Same workload without tracing overhead | 1,624,834 records; largest observed unread backlog 185,702 records; exact counts match |
| [Synchronous source with reconnects](webflux-synchronous-traced/summary.json) | Twenty two-second windows, 600 first-item connections; inline source, unpaused connection startup, resume before closing clients, tracing enabled | 6,220,150 records; exact global/per-connection counts and native-writer bytes match |
| [Original-source replay](webflux-original-replay/summary.json) | Full MVC workload followed by full WebFlux workload using the archived attempt-1 runner, fixture, and servers | All 24 throughput/latency/retention cases pass, including the previously failing warmup |

Every run finished with zero active source subscriptions. Raw results, server logs, source archives, and per-file SHA-256 hashes accompany each summary. Held-reader backlog is the difference between emitted and consumed records across the whole transport path; it does not measure an adapter-owned queue. These stress windows are diagnostics, not performance baselines.

Reproduce the held-reader diagnostic with:

```bash
./gradlew :benchmarks:streaming-runner:boundaryStress -PstreamFrameworks=webflux \
  -PstreamStressCase=ndjson:sparse -PstreamStressSeconds=2 \
  -PstreamStressBoundaries=20 -PstreamStressHoldReaders=true -PstreamStressTrace=true
```

For the synchronous reconnect run, replace `streamStressHoldReaders` with `streamStressReconnect=true`, `streamStressSynchronous=true`, `streamStressResumeFirst=true`, and `streamStressUnpausedStart=true`. Preserve the output before running another diagnostic.

## Original-source replay identity

The replay extracted [attempt 1's source archive](../attempt-1/measured-sources.zip) into an isolated directory and compiled its runner, protocol reader, publisher fixture, and MVC/WebFlux server classes with Java 25 targeting Java 17. Comparing the two source archives shows exactly one changed file: a failure-only `catch` around the runner's drain assertion saves counts and rethrows. The source, reader, server, and successful delivery paths are unchanged. The unchanged 15-second deadline and exact equality assertion remain in place.

The replay classpath puts those isolated classes before the pinned runtime dependencies and excludes the current benchmark classes/fixture jar. Production libraries used by these two servers match the original source hashes. The [launch command and runtime jar hashes](webflux-original-replay/replay.json), [source archive](webflux-original-replay/measured-sources.zip), and [execution log](webflux-original-replay/replay.log) preserve that identity. Running MVC first also repeats the client workload preceding the original WebFlux timeout. This is a source/runtime replay on the same development machine, not a reconstruction of every transient OS or scheduling condition.

## Release evidence assessment

The accepted Phase 7 criteria require passing current tests, independent publication consumers, claimed native-image checks, recorded benchmarks, and compatibility documentation. Those checks now pass; the [requirement audit](../../../../RELEASE_AUDIT.md) links their evidence. No count assertion, timeout, retention threshold, or required framework case was relaxed to obtain those results.

The historical timeout remains a known observation with an unknown cause, rather than a claimed fixed defect or confirmed data-loss finding. Further investigation is warranted if it recurs; the added diagnostics preserve the information missing from the original attempt. Completing local implementation and verification does not imply public publication, hosted CI execution, or hard real-time delivery guarantees.
