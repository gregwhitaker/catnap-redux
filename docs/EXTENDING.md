# Adding an integration

Keep dependencies directed toward the core: a framework adapter depends on its JSON binding and `catnap-http`; a JSON binding depends on `catnap-core`. The core must remain independent of serializers, HTTP frameworks, Reactor, and Kotlin. New convention plugins belong in their own `build-logic` project.

## A native JSON tree binding

Implement `JsonTreeAdapter<N>` for an already materialized native node type. The interface and ownership rules are documented in [the core API](CORE_API.md). A binding needs six projection operations plus scalar access for predicates:

| Operation | Required behavior |
|---|---|
| `kind` | Distinguish objects, arrays, and scalars; JSON null is a scalar |
| `fields` | Iterate unique, non-null field names in source order |
| `elements` | Iterate arrays in source order |
| `object` | Construct a new object using the supplied insertion-ordered members |
| `array` | Construct a new array using the supplied elements without reordering |
| `copyScalar` | Preserve type, exact value, and precision; copy mutable values |
| `scalarValue` | For CATNAP predicates, expose an immutable `JsonScalar` containing a string, boolean, exact `BigDecimal`, or null |

Factories receive fresh collections owned by the evaluation and may retain them. Never return an input mutable container as a result. Immutable scalar sharing is safe; mutable binary/scalar values need independent copies. Reject lazy POJO/missing-node placeholders that have not yet become JSON. Resolve duplicate object keys during parsing and reject cyclic structures rather than treating them as JSON trees.

Put parsing/writing helpers in the binding module, outside the interface. Use the application's configured serializer and complete declared generic/type/view context when converting domain objects. Avoid a second unconfigured mapper that changes field names, ignores application modules, or loses number precision. Reject trailing JSON values; enforce raw input bytes and parser nesting before allocating unbounded trees. The evaluator's work limit does not bound serialization or parsing allocation.

For a repository module, use the existing convention plugins:

```kotlin
plugins {
    id("catnap.publishing")
    id("catnap.testing")
}
dependencies {
    api(project(":catnap-core"))
    // The native JSON library is this module's dependency, never the core's.
    testImplementation(project(":catnap-testkit"))
}
```

An external integration can use the published Catnap BOM and versionless `catnap-core`/`catnap-testkit` coordinates. Select a Java target no higher than the native library needs, document it, and test on that minimum runtime.

Read resources under `/com/github/gregwhitaker/catnap/conformance/v1/` from the testkit classpath. Execute **every** `queries` variant in `projection.json` and the JSON payloads in `streams.json`, comparing with their independently specified `expected` values. Use arbitrary-precision numeric comparison; object member order is not semantically significant, but arrays, strings, nulls, and JSON number values are. Add native-specific tests for two-way mutation isolation, custom scalars, binary values, generic serializers, Unicode, malformed/trailing JSON, depth limits, and numeric precision. The corpus schema and diagnostic positions are described in [its README](../catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md).

## A framework adapter

Bind query state before endpoint invocation with `ProjectionService.bind(decodedParameterValues, endpointEnabled)`. Pass all decoded occurrences, without trimming or concatenating them. Map `QueryRequestException` to the existing HTTP 400 problem contract. Missing/ASCII-blank parameters and disabled projection use the native response path without building another tree. Global disablement takes precedence over endpoint settings; a method policy takes precedence over a controller policy.

Match `ProjectionSettings.DEFAULT`, including the `CATNAP` dialect and `fields` parameter, and expose `GOOGLE` and `LINKEDIN` as explicit options. Test the default without a dialect override and verify configured alternatives.

Carry the immutable `ProjectionRequest` through the framework's request/exchange attributes or an explicitly captured value. Do not rely on thread-local request state. Compiled queries can be reused across threads; mutable encoders, input buffers, and payload trees belong to one response/connection.

Locate the framework's actual serialization boundary before transport commitment. Preserve its selected converter/writer, declared generic type, view, and custom writer context. Use a bounded capture sink where a native tree conversion would bypass those settings. Re-encode only the selected JSON. Use `ResponsePolicy` for eligible media/status behavior and stale representation headers. Retain unrelated headers and status; apply transport compression after projection. If the framework cannot expose the required context automatically, provide an explicit helper and document its return type and serializer contract.

Ordinary finite publisher responses represent one complete JSON array. Accumulate serialized bytes under a total-document bound, cancel at that bound, and evaluate only after completion. Do not collect an unlimited source list or project each finite item under a per-item bound while claiming a whole-response bound.

## NDJSON and SSE

Use a connection-owned `JsonStreamEncoder<T>` with a serializer callback that writes one complete UTF-8 JSON value and a strict projection callback. It bounds serialized input and selected output; encoded raw JSON always invokes validation, even when projection is inactive. `StreamPayload` separates typed JSON, encoded JSON, and intentional text. A failed encoder is poisoned and cannot resume ordinary payload processing.

For byte-chunk input, use `NdjsonFramer` incrementally. Preserve UTF-8 sequences and record delimiters split across chunks. Consume records lazily from the current chunk; do not materialize a list of every record in an arbitrarily large chunk. Bound both source chunks and records. For pull input, `NdjsonReader` and `NdjsonWriter` support bounded record processing. The owner must close input streams on completion, failure, cancellation, and shutdown.

Let the framework own transport and SSE formatting. Project only JSON `data`; keep text, IDs, event names, retry values, comments, and control events. Keep JSON null distinct from absent data. Preserve record/event count even when a projection produces `{}` or CATNAP predicates leave an empty array inside the payload. Bound combined event metadata as well as payload data.

Prepare the first payload before committing headers when the native API permits it. Wait for write acknowledgment or suspending send completion before requesting more work. Use finite pending-send/executor capacities for imperative APIs and reject overflow explicitly. Release temporary payload trees and pooled buffers after each send. Propagate client cancellation to sources and owned resources, including before subscription and during a pending write. A post-commit failure terminates the response; a protocol-specific SSE error event is optional, bounded, unprojected, and followed by termination. It must not replace cancellation or native transport failure handling.

## Evidence for an adapter release

Implement the shared example routes and run `FiniteResponseContract` and `StreamingResponseContract` for Google/LinkedIn and `FiniteResponseContract.catnapCases` for CATNAP. The wire tests verify serializer configuration, shapes, status, bypass, metadata, and order. `StreamingLifecycleContract` uses real HTTP clients and `StreamLifecycleProbe` gates to verify early output, separate connection queries, slow-consumer bounds, disconnect cancellation, and errors before/after commitment. See the existing example implementations for the route and source-control protocol.

Add demand/queue tests for the adapter's own handoff; HTTP tests alone cannot identify an accidental prefetch queue. Include subscribers using different queries against a reusable source, concurrent subscriptions, resource release, and increasing stream durations at a fixed item size. Retained memory must stay bounded by concurrency and configured buffers rather than total item count. Framework source queues and native transport buffers should be identified separately.

Add the module to `settings.gradle.kts`, explicit root verification tasks, and `gradle/published-modules.txt`. Extend the independent publication consumers and dependency-boundary assertions so its POM and Gradle metadata are both exercised. Attach nonempty source/API documentation jars, add the framework/serializer/minimum-Java entry to compatibility documentation, and run native-image tests if claiming native support. Keep performance/native jobs separate from ordinary checks. Publishing a dependency that compiles is insufficient without these lifecycle and consumer checks.
