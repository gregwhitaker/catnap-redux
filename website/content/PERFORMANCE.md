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

## Results and CI

JMH writes results under `benchmarks/build/results/`; the HTTP runner writes under `benchmarks/streaming-runner/build/results/`. These directories are ignored by Git. The [Build workflow](https://github.com/gregwhitaker/catnap-redux/blob/main/.github/workflows/ci.yml) uploads them as artifacts when its manual benchmark inputs are enabled.

Keep raw samples, logs, and generated source snapshots in build output or CI artifacts. Identify the source revision, runtime, host, payloads, queries, and concurrency when reporting measurements. A result applies to that workload and environment; rerun it when those inputs change.

The built-in JMH query workloads currently exercise GOOGLE and LINKEDIN field selection. Add representative CATNAP predicate workloads before making claims about predicate compilation or array-filtering performance. See the [release checklist](RELEASING.md) for candidate verification and the [native guide](NATIVE_IMAGES.md) for native correctness checks.
