# Local JMH baseline — November 16, 2026

All 56 cases completed: two independent forks, three 1-second warmups per fork, five 1-second measured iterations per fork, one benchmark thread, and the GC profiler. Latencies below are means ± JMH’s 99.9% confidence intervals. Allocation is bytes allocated per operation, expressed in KiB; it is not retained heap.

Environment: Amazon Corretto 25.0.5+10-LTS on macOS 15.7.4/aarch64, 10 available processors, 64 GiB physical RAM. Each fork used `-Xms256m -Xmx512m`. Other Gradle builds were kept idle during this run. This was a shared development machine, not an isolated performance lab.

[Raw JMH results](jmh.json) contain every sample and profiler metric. [Environment and workload metadata](environment.json) record queries, exact byte sizes, and the measured-source SHA-256. The [measured-source archive](measured-sources.zip) preserves the exact files behind that hash as the working tree evolves. [Methodology and commands](../../../benchmarks/README.md) explain each operation.

## Complete response processing

Times are microseconds per complete document. The native baseline serializes the full input. The projection pipeline captures the native writer’s bytes, parses, evaluates a precompiled query, and serializes the selected result under the default byte bound. Query binding/compilation and HTTP transport are excluded. Selected responses are smaller, so this is not an equal-wire-size transport comparison.

| Workload | Input bytes | Selected bytes | Native µs | Complete projection µs | Native allocated KiB | Projection allocated KiB |
|---|---:|---:|---:|---:|---:|---:|
| SMALL | 156 | 47 | 0.297 ± 0.002 | 2.035 ± 0.023 | 0.66 | 21.15 |
| LARGE_ARRAY | 654,251 | 150,443 | 877.687 ± 2.428 | 4,611.779 ± 176.676 | 1,183.62 | 15,860.38 |
| DEEP | 1,574 | 496 | 2.769 ± 0.006 | 15.754 ± 0.189 | 4.25 | 87.48 |
| SPARSE | 86,821 | 23 | 123.134 ± 0.330 | 320.963 ± 3.569 | 155.15 | 900.01 |
| WILDCARD | 10,742 | 1,270 | 14.623 ± 0.081 | 62.057 ± 0.621 | 19.83 | 197.64 |

## Isolated stages

Microseconds per operation. `domainToTree` uses native tokens; `parseTree` starts from encoded JSON. These are alternative construction paths. Isolated stage means are not additive: object reuse, JIT decisions, and allocation differ from the complete pipeline.

| Workload | Parse tree µs | Domain to tree µs | Evaluate existing tree µs | Serialize selected tree µs |
|---|---:|---:|---:|---:|
| SMALL | 0.411 ± 0.001 | 0.334 ± 0.002 | 0.323 ± 0.002 | 0.197 ± 0.001 |
| LARGE_ARRAY | 1,267.920 ± 2.547 | 1,383.342 ± 133.241 | 1,394.151 ± 86.954 | 638.295 ± 11.970 |
| DEEP | 4.391 ± 0.058 | 4.791 ± 0.031 | 6.023 ± 0.290 | 1.368 ± 0.058 |
| SPARSE | 182.467 ± 0.921 | 163.232 ± 5.844 | 11.901 ± 0.479 | 0.234 ± 0.002 |
| WILDCARD | 24.530 ± 0.053 | 21.319 ± 0.864 | 14.069 ± 0.054 | 5.686 ± 0.008 |

## Query compilation

Uncached compilation is in microseconds; hot-cache lookups are in nanoseconds. Each cache case uses one hot key, a capacity of 256, and one thread. It does not model concurrent contention or cache churn.

| Workload query | Google compile µs | LinkedIn compile µs | Google hot-cache ns | LinkedIn hot-cache ns |
|---|---:|---:|---:|---:|
| SMALL | 0.293 ± 0.016 | 0.259 ± 0.001 | 8.831 ± 0.018 | 8.832 ± 0.038 |
| LARGE_ARRAY | 0.252 ± 0.012 | 0.167 ± 0.001 | 8.844 ± 0.031 | 8.839 ± 0.021 |
| DEEP | 2.904 ± 0.014 | 3.029 ± 0.009 | 8.851 ± 0.018 | 8.832 ± 0.034 |
| SPARSE | 0.128 ± 0.001 | 0.118 ± 0.000 | 8.823 ± 0.032 | 8.865 ± 0.288 |
| WILDCARD | 0.116 ± 0.004 | 0.118 ± 0.000 | 8.847 ± 0.044 | 8.810 ± 0.033 |

## Prepared stream records

The record contains an integer ID and a string with the stated padding size; JSON syntax adds 21 bytes. Native, bypass, and full-field projection produce byte-identical JSON in the validation tests. This table excludes the final native NDJSON delimiter, SSE envelope, and HTTP transport.

| Padding bytes | Path | Encoding µs per record | Allocated KiB per record |
|---|---|---:|---:|
| 1,024 | nativeRecordEncoding | 0.930 ± 0.024 | 2.54 |
| 1,024 | bypassRecordEncoding | 4.750 ± 0.154 | 11.57 |
| 1,024 | projectedRecordEncoding | 4.443 ± 0.147 | 24.27 |
| 65,536 | nativeRecordEncoding | 64.594 ± 0.150 | 291.39 |
| 65,536 | bypassRecordEncoding | 114.713 ± 13.938 | 440.68 |
| 65,536 | projectedRecordEncoding | 217.299 ± 1.250 | 1,011.48 |

For the small record, bypass and active projection have similar measured latency despite different allocation. This code-path/JIT result is not evidence that adding projection generally makes serialization faster. The 64 KiB bypass timing also has a wider confidence interval; use the raw samples rather than treating a single mean as a fixed cost.

## Scope and complementary evidence

This baseline supports cost/allocation comparisons for the engine and Jackson 3 boundary. It does not measure first-item HTTP latency, sustained framework streaming throughput, server retained heap, native/direct-buffer retention, or behavior under concurrent production load. Those are covered separately by the [recorded HTTP streaming jobs](http/README.md) for their stated workload. It provides no hard real-time guarantee and does not rank frameworks.
