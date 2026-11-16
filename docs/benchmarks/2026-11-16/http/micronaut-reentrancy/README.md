# Micronaut synchronous-source investigation

The original direct-memory failure is reproducible on Micronaut core 5.2.23 / Netty 4.2.20.Final, with both ordinary native responses and Catnap's original synchronous helpers. Catnap now uses an asynchronous handoff with prefetch one before native response writing. All six Catnap protocol/query combinations pass the same reproducer with that change. Native endpoints bypass Catnap and retain the framework behavior.

## Reproducer and controls

Each case uses two unlimited-rate HTTP connections, 20 production windows of 0.3 seconds, and 30 fresh first-item requests before each pair (600 short requests per case). The server heap and maximum direct memory are each 512 MiB. Connections start with production enabled. Every pause requires exact agreement between emitted and delivered records, and cleanup requires no active source subscriptions. Fatal server errors fail the run even when the delivery counts match.

`micronaut-matrix.json` records the source/cleanup matrix against **native NDJSON**, before any adapter change:

| Source | Cleanup | Result |
|---|---|---|
| Synchronous request/drain | Resume producer before closing clients | Direct-memory exhaustion |
| Batches of 32 on the benchmark scheduler | Resume before closing | Passed |
| Synchronous request/drain | Close clients before resuming | Passed |
| Batches of 32 on the benchmark scheduler | Close before resuming | Passed |

`micronaut-protocol-matrix.json` repeats the failing combination on full-field Catnap NDJSON, native SSE, and full-field Catnap SSE. All three exhaust direct memory. The logs preserve allocation failures near 512 MiB. The fixture's synchronous mode is demand-aware and uses a trampoline for reentrant requests; it does not ignore demand or retain a payload queue.

## Mechanism and adapter change

The pinned framework source and failure stacks show a reentrant demand path through `PipeliningServerHandler.writeSome`, `ConcatenatingSubscriber.onBytesConsumed`, and synchronous source production. `NettyConcatenatingSubscriber.add` defers its superclass's byte accounting through `EventLoopFlow`. A nested emission from inside an already deferred callback can enqueue another body while `complete()` still sees the previous forwarded-byte count and requests another. A synchronous producer can keep that callback from returning, preventing the pending accounting tasks from running. This is our diagnosis from the pinned implementation and reproducer, not an upstream issue resolution.

Primary source artifacts: [Micronaut HTTP server Netty 5.2.23](https://repo.maven.apache.org/maven2/io/micronaut/micronaut-http-server-netty/5.2.23/micronaut-http-server-netty-5.2.23-sources.jar), [HTTP body implementation](https://repo.maven.apache.org/maven2/io/micronaut/micronaut-http/5.2.23/micronaut-http-5.2.23-sources.jar), and [Netty event-loop flow](https://repo.maven.apache.org/maven2/io/micronaut/micronaut-http-netty/5.2.23/micronaut-http-netty-5.2.23-sources.jar).

`CatnapMicronautStreams` now hands off prepared records/events through `publishOn(Schedulers.parallel(), true, 1)`. Demand returns to Micronaut before the next native write callback. The helper can hold one prepared item in addition to the current delivery, preserves ordered terminal signals, and propagates cancellation. It does not create an unbounded item queue or make blocking serializers safe. Reactor context and the explicitly captured serializer/type/view remain attached. The shared scheduler is framework infrastructure, not a per-response executor.

Unit tests cover bounded preparation, cancellation during a pending downstream callback, context, overflow cleanup, signal order, and response ownership. The example HTTP suite also passes. See the [integration guide](../../../../MICRONAUT_INTEGRATION.md) for the updated execution contract.

## Post-change stress results

`micronaut-handoff-matrix.json` records the same synchronous-source, resume-before-close reproducer on every Catnap mode:

| Protocol | Query mode | Delivered records | Final active subscriptions | Final Netty direct bytes |
|---|---|---:|---:|---:|
| NDJSON | Bypass | 1,225,983 | 0 | 3,407,872 |
| NDJSON | Full fields | 1,134,246 | 0 | 1,441,792 |
| NDJSON | Sparse | 1,608,891 | 0 | 1,310,720 |
| SSE | Bypass | 1,095,222 | 0 | 1,835,008 |
| SSE | Full fields | 1,063,318 | 0 | 1,703,936 |
| SSE | Sparse | 1,605,821 | 0 | 1,179,648 |

Each case directory contains its Gradle log, server log, JSON results, and a source archive identified by SHA-256 hashes in `summary.json`. The matrix files retain exact commands and exit codes. These are regression diagnostics, not throughput comparisons or proof of bounds for every native transport/application configuration. The [refreshed full-duration measurements](../attempt-4-micronaut/summary.json) pass all 12 cases, and the [native-image rerun](../../../../native/2026-11-16/micronaut-handoff/summary.json) passes all 55 tests. Older measurement snapshots remain historical evidence. The updated delivery-window report records the throughput cost of the added scheduling boundary.

To reproduce a Catnap case:

```bash
./gradlew :benchmarks:streaming-runner:boundaryStress \
  -PstreamFrameworks=micronaut -PstreamStressCase=ndjson:all \
  -PstreamStressSeconds=0.3 -PstreamStressBoundaries=20 \
  -PstreamStressReconnect=true -PstreamStressUnpausedStart=true \
  -PstreamStressSynchronous=true -PstreamStressResumeFirst=true
```

The ordinary benchmark source and close-before-resume cleanup remain the defaults. Diagnostic flags deliberately restore the adverse conditions; they do not alter production application configuration.
