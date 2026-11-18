# Core compiler and evaluator

`catnap-core` implements the [version 1 query contract](QUERY_SPECIFICATION.md) on Java 17 with no third-party runtime dependencies. It compiles Google/LinkedIn field selections and CATNAP selections with RSQL predicates into an immutable `Selection` AST and projects materialized JSON through `JsonTreeAdapter<N>`.

## Compile and evaluate

The core requires an explicit dialect on every compile call. Use `QueryDialect.CATNAP` for field selection and RSQL-subset predicates, or select `GOOGLE` or `LINKEDIN` for their field-selection profiles. HTTP integrations and the standalone NDJSON launcher default to `CATNAP` when no dialect is configured.

```java
CompiledQuery query = Catnap.compile("name,price(amount)", QueryDialect.CATNAP);

// `document` and `adapter` use the application's native materialized JSON node type.
N projected = query.evaluate(document, adapter);
Selection ast = query.selection();
```

`Catnap.compile` has no global cache. A `CompiledQuery` contains only its selection tree and is safe to reuse concurrently. Each evaluation has independent counters, output containers, and active wildcard/literal selections. Input trees must not be modified concurrently, and shared adapters must themselves be thread-safe.

`Selection.fields()` exposes an unmodifiable map of literal field names in deterministic name order. `Selection.wildcard()` is a separate optional branch; quoted wildcard names remain ordinary literal keys. `wholeValue()` identifies a complete selected value. Duplicate literals merge recursively, and whole-value selection dominates children of that literal. Wildcard/literal unions are evaluated together without expanding the AST into every combination. Thus semantically equivalent wildcard expressions need not have identical ASTs; ordinary paths and child-list spellings do normalize identically.

CATNAP adds immutable `Selection.filters()` alternatives, each pairing a `QueryPredicate` with its own child `Selection`. Comparisons contain typed `JsonScalar` arguments; AND/OR groups retain their term order. The evaluator merges only branches whose predicates match the current element. This avoids mixing fields from differently filtered selections.

```java
CompiledQuery images = Catnap.compile("images(url)[size==medium;width>=640]", QueryDialect.CATNAP);
N selected = images.evaluate(document, adapter);
```

Result object ordering follows the input adapter's field order, independently of AST ordering. Without a CATNAP predicate, arrays preserve order and count; scalars and nulls pass unchanged. Explicit CATNAP predicates retain matching direct object elements in order. Google and LinkedIn do not filter array items. Empty objects remain present. HTTP absence/blank-parameter bypass is an adapter responsibility, not a special core query.

## JSON tree adapter contract

Implement `JsonTreeAdapter<N>` for the native tree type:

| Operation | Responsibility |
|---|---|
| `kind(node)` | Identify `OBJECT`, `ARRAY`, or `SCALAR`; JSON null is a scalar |
| `fields(object)` | Supply unique, non-null field names and native values in source iteration order |
| `elements(array)` | Supply native elements in source order |
| `object(fields)` | Construct an object using the fresh insertion-ordered map supplied by the evaluator |
| `array(elements)` | Construct an array using the fresh ordered list supplied by the evaluator |
| `copyScalar(scalar)` | Preserve value, JSON type, and precision; copy mutable scalars or share immutable ones |
| `scalarValue(scalar)` | For predicates, return an immutable `JsonScalar` containing a `String`, `Boolean`, exact `BigDecimal`, or Java null for JSON null |

`scalarValue` has a default implementation that throws `UnsupportedOperationException`. Existing custom adapters remain compatible with field-only queries. Implement this method to enable predicates; unsupported access becomes an `EvaluationException` at the inspected field's JSON Pointer. All bundled bindings implement it. Numbers must not be rounded through `double`, and strings must not be coerced to numbers/booleans. See [predicate semantics](QUERY_SPECIFICATION.md#catnap-array-predicates).

Factories may retain their supplied collections: the evaluator creates them for that result and does not mutate them after construction. All traversed containers, including whole selected subtrees, are reconstructed. Never modify input nodes or return an input mutable container as a new result. The adapter owns mutable scalar copying. Java null can represent JSON null when the adapter supports it. Duplicate keys must have been resolved before projection. Trees should be acyclic; shared source subtrees are allowed and copied independently.

Core tests use a Java map/list adapter with immutable Java scalar values. Jackson is only a test dependency of the core used to load fixtures. [Jackson 2/3 and Kotlin serialization bindings](JSON_INTEGRATIONS.md) are provided in separate modules; no framework serialization is intercepted by the core itself.

## Limits and errors

```java
CompileOptions compilation = new CompileOptions(16_384, 64, 4_096);
EvaluationOptions evaluation = new EvaluationOptions(128, 1_000_000);
CompiledQuery query = Catnap.compile("items(id)", QueryDialect.CATNAP, compilation);
N result = query.evaluate(document, adapter, evaluation);
```

These are the defaults, chosen as initial guardrails rather than benchmark-derived throughput or latency guarantees. Compilation depth counts selector segments from one, including slash paths. Selector count includes duplicates, wildcard segments, and selectors later dominated by a whole-value selection. For CATNAP, the same count also includes predicate groups, comparisons, and each argument; nested predicate groups/comparisons add to selector depth. Query length counts UTF-16 units before trimming (the compiler never trims). All supplied syntax is validated even when a preceding whole-value selector would make it redundant.

Evaluation root depth is zero. One work unit is charged for each visited node, each inspected object member, and each active selection branch matched against a member. CATNAP also charges for predicate nodes, predicate member scans, scalar inspection, argument comparisons, and rejected elements. Unmatched fields therefore consume work; their descendants are not visited. Whole selected subtrees also consume traversal work. Work does not measure elapsed time, scalar byte length, iterator implementation cost, or input parsing/allocation. JSON integrations and HTTP adapters must separately bound raw input sizes and serialization work. Limits apply independently to each `evaluate` call, so a stream can reuse a plan with a fresh payload budget for every item.

Compilation depth can be configured from 1 to 256; evaluation depth from 0 to 256. The upper ceilings bound recursive Java stack use and are enforced when options are constructed. Length, selector, and work bounds must be positive. Limit checks run during compilation/traversal and do not return partial plans or results.

| Exception | Structured details |
|---|---|
| `QuerySyntaxException` | Stable `code()` and `position()` containing UTF-16 `offset`, `line`, and `column`, as specified in the query contract |
| `LimitExceededException` | `limit()`, `maximum()`, `actual()`, plus compilation `position()` or evaluation `path()`; the other location is null |
| `EvaluationException` | `code()` is `ADAPTER_FAILURE`; `path()` identifies the failing node and `getCause()` preserves the adapter's runtime exception |

Evaluation paths use JSON Pointer escaping (`~0` for `~`, `~1` for `/`), with an empty string for the root. Work exhaustion during object-member scanning points to the containing object. Compilation length errors point to the first UTF-16 offset beyond the allowed prefix. `actual()` reports the observed count, saturating at `Long.MAX_VALUE` for an exhausted work counter at that value. Null compiler arguments/adapter/options are API argument errors; a null document can be legitimate JSON null. JVM errors are not caught or converted.

## Optional bounded compilation cache

```java
QueryCompiler compiler = new QueryCompiler(256);
CompiledQuery query = compiler.compile("items:(id)", QueryDialect.LINKEDIN);
```

This instance retains at most 256 successful plans using least-recently-used eviction. Capacity zero disables caching. Keys include the exact expression, dialect, and all `CompileOptions`; whitespace-different expressions have separate entries. Failed compilations are not cached. Payloads, adapters, evaluation options, and per-connection state are never stored. Maximum query length is checked before a cache lookup. Capacity bounds entry count, so choose it together with compilation length/selector limits when budgeting memory.

Cache access is synchronized; compilation happens outside the lock. Concurrent misses may compile redundantly and then return the winning cached plan. `cacheSize()` returns the current bounded count. `clearCache()` removes retained entries, while already in-progress compilations may populate it again. Static `Catnap.compile` does not use this cache.

## Verification

The core executes all 178 projection query variants, 79 syntax-error cases, and 26 logical stream variants from the shared corpus. Tests also cover immutable ASTs, two-way source/result mutation isolation, precision, field/array ordering, concurrent query/cache reuse, eviction and cache keys, adapter failures, limit boundaries, maximum supported nesting, and deterministic malformed-input generation.

```bash
./gradlew :catnap-core:test
./gradlew compatibilityTest
./gradlew check
```

`compatibilityTest` runs the same core tests on Java 17, 21, and 25. The dependency-boundary check verifies that core runtime dependencies remain empty. Logical stream tests compile once and project each JSON payload; transport framing, HTTP parameter binding, backpressure, and cancellation are verified separately by the framework adapters' HTTP and lifecycle tests. `publicationSmokeTest` additionally checks the published core jar on all three runtimes in independent consumer builds.
