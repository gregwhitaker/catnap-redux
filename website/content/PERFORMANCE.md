# Performance and operating bounds

Catnap reduces response fields after the application serializes its data. The benefit depends on the payload, selected fields, and transport cost. Serialization, tree construction, query evaluation, and output encoding still consume CPU and memory. Measure the endpoint with representative queries and concurrency before choosing limits.

## Initial operating choices

These defaults are guardrails, not throughput or latency guarantees. The [core API](CORE_API.md#limits-and-errors) and framework guides describe their configuration.

| Setting or choice | Current behavior | Application guidance |
|---|---|---|
| Compiled-query reuse | Immutable queries; optional HTTP cache of 256 successful queries | Reuse plans to avoid repeated compilation. Set a finite cache capacity appropriate to the variety of client queries. |
| JSON payload byte bound | 1 MiB input and selected output for explicit bounded paths | A byte cap is not a heap cap. Budget for trees, temporary encoding buffers, concurrent responses, and framework transport buffers. |
| Query bounds | 16,384 UTF-16 units, depth 64, 4,096 selectors | CATNAP predicates share these bounds: groups, comparisons, and arguments contribute to complexity. |
| Evaluation bounds | Depth 128 and 1,000,000 work units per document/item | Work is a structural counter, not elapsed time. Include large arrays, wide objects, and predicate comparisons in load tests. |
| Sparse field selection | Removes object fields; array count changes only with an explicit CATNAP predicate | Tiny output can still require substantial input serialization and traversal. Apply database filtering and authorization in the application. |
| Streaming execution | One bounded logical record or JSON payload at a time | Measure slow consumers and concurrent connections. Keep source queues and framework buffers bounded. |

Keep the application's serialization authoritative. Standalone bindings can avoid an encoded-JSON round trip through native tree conversion; an HTTP adapter may need bounded writer capture to retain generic types, views, and custom writer behavior. Isolated benchmark stages are not additive.

## Measure your application

Compare the ordinary response path, projection bypass, full-field selection, and the sparse selections clients actually request. Include predicate compilation, membership lists, match rates, and array sizes when using CATNAP filters. Compile once per query or stream connection where possible.

For finite responses, measure serialization and the complete HTTP request, including allocation and peak memory at realistic concurrency. For NDJSON/SSE, also measure first-item delivery, sustained throughput, slow readers, cancellation, and retained heap/direct memory over long connections. Total allocated bytes per operation do not measure retained memory.

The [benchmark guide](https://github.com/gregwhitaker/catnap-redux/blob/main/benchmarks/README.md) provides JMH and HTTP measurement commands. The streaming smoke runner checks protocol behavior and cleanup; its timings are not performance evidence. Native quick-build tests establish correctness only.

## Recorded measurements

The November 16 records predate the CATNAP dialect and default change. They establish a field-selection baseline for their archived source and workload; they do not measure predicate compilation or array filtering.

| Record | Coverage |
|---|---|
| [JMH baseline](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/README.md) | 56 Java 25 cases separating compilation, tree construction, evaluation, serialization, full processing, and stream-item preparation |
| [HTTP measurements](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/README.md) | Five frameworks, 40 throughput/latency cases, and 20 retention cases with bounded concurrency and open connections |
| [Micronaut handoff measurements](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/attempt-4-micronaut/summary.json) | 12 cases after introducing the bounded asynchronous handoff |

For scale, the recorded 156-byte document's complete projection pipeline averaged 2.035 µs, and the 654,251-byte, 4,096-element array averaged 4.612 ms. These local CPU measurements exclude HTTP transport and request binding. The records contain raw samples, allocation data, environment details, and source identities; they do not establish universal latency targets or a cross-framework ranking.

## Streaming measurements and recorded investigations

The [WebFlux drain investigation](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/webflux-drain-audit/README.md) preserves a 15-second timeout with no recorded delivered count. Later boundary, paused-reader, synchronous-source, and archived-source replay checks passed. The original cause remains unknown; no WebFlux production fix is claimed.

The [Micronaut investigation](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/micronaut-reentrancy/README.md) reproduces direct-memory exhaustion with a synchronous source and resume-before-close cleanup. Catnap's bounded asynchronous handoff uses prefetch one and passes the recorded adverse-condition checks. It has a measured cost: full-field NDJSON throughput fell from about 182,000 to 52,500 records/second, and SSE from about 172,000 to 49,700 in those local runs. The linked records preserve the source, cleanup conditions, allocation/retention results, and limits of that comparison.

Current correctness verification is tracked separately in the [native guide](NATIVE_IMAGES.md) and [release checklist](RELEASING.md). Earlier reports and unresolved observations remain archived when new measurements are added.
