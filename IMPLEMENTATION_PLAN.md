# Catnap implementation plan

Status: Phases 1–7 are implemented and locally verified. Publication checks, the full test matrix, all five framework streaming smoke suites, and the claimed Quarkus/Micronaut native suites pass. The 56-case JMH baseline and five-framework HTTP measurements are recorded. The [requirement audit](docs/RELEASE_AUDIT.md) maps each accepted requirement to evidence and preserves the unexplained historical WebFlux drain timeout. Public release publication and hosted CI have not been run.

This plan describes a new major version of Catnap for JVM applications. It uses the [original Catnap repository](https://github.com/gregwhitaker/catnap) as a behavioral reference and introduces a framework-independent JSON projection engine with Gradle-built integrations.

The first release includes ordinary JSON responses, NDJSON, and SSE events with JSON payloads. It supports Google-style and LinkedIn-style field selection, standalone use, and adapters for Spring Boot, Micronaut, Quarkus, and Ktor.

## 1. Accepted architecture

Use option 1: materialize the actual JSON data into a tree, then evaluate a compiled query against that tree.

There are two distinct representations:

- **Query AST:** an immutable selection plan compiled from a Google-style or LinkedIn-style expression. It can be reused across documents and stream items.
- **JSON data tree:** the objects, arrays, and scalar values being queried. For an ordinary response, this represents the complete document. For NDJSON or SSE, it represents one record or event payload at a time.

The core will define a small `JsonTreeAdapter<N>` interface for reading nodes, enumerating fields and elements, constructing result containers, and copying selected subtrees. JSON integrations implement this interface for their existing materialized tree types. The evaluator does not require Jackson types in its public API or a second complete copy in a proprietary JSON representation.

Dependency direction is framework adapter → JSON integration → core. The core has no HTTP, servlet, framework, Jackson, Reactor, or Kotlin runtime dependency.

An illustrative API is:

```java
CompiledQuery query = Catnap.compile(
    "name,price(amount)",
    QueryDialect.GOOGLE
);

JsonNode result = query.evaluate(
    document,
    Jackson3TreeAdapter.INSTANCE
);
```

Public names are provisional until the API contract is implemented. Evaluation must preserve the source document. For mutable tree implementations, mutating a result must also leave the source document unchanged. Immutable nodes may be safely shared.

## 2. First-release scope and defaults

### Query languages

Implement two documented dialect profiles that compile into the same query AST:

- Google-style fields: comma-separated selections, slash paths, parenthesized child selections, and documented wildcard behavior. See the [Google fields reference](https://developers.google.com/workspace/drive/api/guides/fields-parameter).
- LinkedIn-style field projections: comma-separated selections, colon-prefixed child selections, and documented map wildcard behavior. See the [LinkedIn projections reference](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/projections).

Equivalent selections must produce equivalent results:

```text
Google:   name,price(amount)
LinkedIn: name,price:(amount)
```

The supported grammar and semantics are defined in the [version 1 query specification](docs/QUERY_SPECIFICATION.md). These profiles provide partial-response field selection; they do not imply support for every vendor-specific API behavior.

### Projection contract

- Nested objects and arrays are supported, including arrays of objects at the document root.
- Overlapping selections are merged deterministically. Selecting an entire subtree includes that subtree even when another selector names a child.
- An omitted or blank HTTP query uses the framework's normal response path without building an additional tree.
- Missing fields are omitted by default. Projection removes object fields; primitive values and nulls pass unchanged, including at the root or under child selections. Arrays propagate the selection to each element without changing their shape or count. Empty containers retain their type. These choices are specified and covered by Phase 2 fixtures.
- Selected JSON null values remain null. Preserve array order and element count.
- An empty projected object remains `{}`. Projection alone does not change a successful response into HTTP 204.
- Query field names refer to the JSON representation exposed by the application's configured serializer.
- Framework adapters configure the dialect explicitly. The first release does not depend on ambiguous syntax auto-detection.
- The default HTTP parameter is `fields`, with a configurable alternative.

The original Catnap value predicates, such as `[size=thumbnail]`, and legacy JSONP behavior are separate future features. This is a new major API, with migration guidance for existing users.

## 3. Gradle build and compatibility

Use a Gradle multi-project build with Kotlin DSL. Write the core and Java framework integrations in Java; use Kotlin where it benefits the Ktor and Kotlin serialization integrations.

| Component | Validated Phase 1 baseline |
|---|---|
| Gradle wrapper | 9.7.0, with distribution checksum |
| Build JVM | Java 25 |
| Core and general-purpose Java library target | Java 17 |
| Core compatibility test runtimes | Java 17, 21, and 25 |
| Kotlin Gradle plugin | 2.4.21 |
| Initial framework versions | Spring Boot 4.1.2, Micronaut platform 5.2.6, Quarkus 3.40.1.2, Ktor 3.6.1 |

These versions build together and pass the Phase 1 HTTP serialization probes. Exact framework, JSON library, test library, and plugin versions are pinned through the version catalog, framework BOMs, and dependency lockfiles. See [Phase 1 findings](docs/PHASE_1.md) for the complete matrix, supported explicit integration paths, and verification limits. Automatic production adapters and the full streaming lifecycle contract remain subsequent phases.

Gradle 9.7.0 matches Quarkus's documented tooling recommendation and the documented Gradle range for Kotlin 2.4.21. See [Quarkus Gradle tooling](https://quarkus.io/guides/gradle-tooling/) and [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html). Use [Gradle toolchains](https://docs.gradle.org/current/userguide/compatibility.html) to separate the JVM running the build from compilation and test targets.

Adapter requirements remain local to their modules. In particular, [Micronaut 5 requires Java 25](https://micronaut.io/2026/05/20/micronaut-framework-5-0-0-released/); this must not raise the core library's minimum Java version. Test published bytecode and dependency compatibility on the advertised minimum runtime.

### Repository layout

```text
catnap/
├── IMPLEMENTATION_PLAN.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build-logic/
│   ├── jvm-conventions/
│   ├── java-library/
│   ├── kotlin-library/
│   ├── testing/
│   ├── publishing/
│   └── benchmark-server/
├── catnap-core/
├── catnap-jackson2/
├── catnap-jackson3/
├── catnap-kotlinx-json/
├── catnap-spring-webmvc/
├── catnap-spring-webflux/
├── catnap-spring-boot-autoconfigure/
├── catnap-spring-boot-starter/
├── catnap-spring-boot-starter-webflux/
├── catnap-micronaut/
├── catnap-quarkus/
│   ├── runtime/
│   └── deployment/
├── catnap-ktor/
├── catnap-bom/
├── catnap-testkit/
├── benchmarks/
└── examples/
    ├── standalone/
    ├── spring-boot-mvc/
    ├── spring-boot-webflux/
    ├── micronaut/
    ├── quarkus/
    └── ktor/
```

Introduce modules as their implementation phases begin.

| Module | Responsibility |
|---|---|
| `catnap-core` | Both parsers, common query AST, evaluator, JSON tree interface, errors, execution limits, and optional bounded query caching |
| `catnap-jackson2` / `catnap-jackson3` | Native tree operations, parsing/writing helpers, and integration with the application's mapper configuration |
| `catnap-kotlinx-json` | Native Kotlin serialization JSON tree operations and helpers |
| `catnap-streams` | Framework-independent bounded NDJSON framing, UTF-8 validation, and record emission |
| `catnap-http` | Shared query binding, endpoint policy annotation, settings, response eligibility, and bounded serialization capture |
| `catnap-spring-webmvc` | Finite JSON and per-item NDJSON/SSE integration for Spring MVC |
| `catnap-spring-webflux` | Finite JSON and per-item NDJSON/SSE integration for Spring WebFlux |
| Spring Boot auto-configuration and starters | Configuration properties, conditional registration, and separate MVC and WebFlux dependency choices |
| `catnap-micronaut` | Native Micronaut JSON tree binding and finite/streaming response integration |
| Quarkus runtime/deployment | Runtime projection integration and build-time extension registration |
| `catnap-ktor` | Finite JSON, NDJSON, and typed SSE integration with Ktor serialization and coroutine lifecycles |
| `catnap-bom` | Version alignment for Catnap artifacts |
| `catnap-testkit` | Shared projection fixtures, JSON integration contracts, and HTTP/streaming test helpers |
| `benchmarks` | Compilation, tree construction, evaluation, serialization, allocation, and streaming benchmarks |

The Quarkus split follows its [extension model](https://quarkus.io/guides/building-my-first-extension/). Spring MVC and WebFlux remain separate so an MVC-only application does not acquire Reactor through Catnap.

### Build conventions

- Use an included `build-logic` build with each convention plugin in its own project: `jvm-conventions`, `java-library`, `kotlin-library`, `testing`, `publishing`, and `benchmark-server`. Each project declares its plugin dependencies and owns a lockfile. Follow [Gradle's guidance for shared build logic](https://docs.gradle.org/current/userguide/intro_multi_project_builds.html).
- Centralize dependency and plugin coordinates in `gradle/libs.versions.toml`; explicitly import the catalog into the included build where needed.
- Scope framework BOMs and framework plugins to the appropriate modules. The Catnap BOM aligns Catnap artifacts without enforcing unrelated framework versions in consumer applications.
- Use `java-library` for Java libraries and the Kotlin JVM plugin only where required. Application packaging plugins belong in examples; the Quarkus extension plugin belongs in its extension module.
- Publish ordinary library JARs with source and documentation artifacts. Do not publish examples or benchmarks as production dependencies.
- Configure dependency locking, reproducible archives, formatting, JUnit Platform tests, and appropriate Java/Kotlin target validation.
- Wire root verification tasks explicitly. Keep benchmarks and native-image jobs separate from ordinary `check` execution.

## 4. NDJSON and SSE design

NDJSON and JSON-bearing SSE are required in the first release. Both reuse the core evaluator and JSON integrations.

```text
Connection opens
    → validate and compile its query
    → for each complete record or JSON event payload:
          serialize into a JSON data tree
          evaluate the compiled query
          encode and emit the projected result
          release temporary references
    → finish or cancel with the source stream
```

The query is fixed for the connection. A new connection can use a different selection. Compiled queries may be shared safely; payload trees and per-connection state must not be retained in a global query cache.

### NDJSON

- Treat each logical JSON value as one record and apply the query relative to that record's root.
- Emit one compact JSON value followed by a newline. Preserve record order and count, including projected `{}` results.
- Prefer projection of typed source items before wire encoding. Where an API accepts already encoded NDJSON, frame complete records incrementally and enforce a maximum record size.
- Handle records spanning transport chunks, multiple records in a chunk, CRLF, and UTF-8 characters split across byte buffers. The version 1 contract accepts LF/CRLF, rejects blank records and nonempty unterminated final records, and emits LF after each compact JSON value.
- Never treat an arbitrary network chunk as a complete JSON value or collect the entire source stream into a list.

### SSE

- Apply the query to the JSON inside the event's `data` payload.
- Preserve event name, ID, retry information, comments, and heartbeat/control messages. SSE framing and reconnection metadata are defined independently of the payload by the [SSE specification](https://html.spec.whatwg.org/multipage/server-sent-events.html).
- Project only payloads explicitly designated as JSON by the endpoint or event configuration. Intentionally textual events pass through according to that configuration; malformed JSON on a declared JSON event is an error.
- Keep one input event as one output event. An empty projection still emits its original envelope with an empty JSON result.
- Delegate SSE framing, escaping, flushing, and connection management to the framework. Operate on complete logical payloads, not individual `data:` lines or encoded byte fragments.
- Preserve original event IDs so the application's reconnect and replay behavior continues to work. Catnap does not implement an event store or replay service.

For `GET /quotes?fields=symbol,price`, the transformation is:

```text
Input:
id: 42
event: quote
data: {"symbol":"ACME","price":12.5,"volume":1000}

Output:
id: 42
event: quote
data: {"symbol":"ACME","price":12.5}

```

Both events are terminated by the blank line required by SSE framing.

### Flow control, cancellation, and failures

- Validate query syntax and query limits before committing response headers. HTTP adapters return a documented client error for an invalid query.
- Preserve downstream demand or suspending-send behavior. Bound any adapter queues, prefetch, and concurrency; preserve item order.
- Propagate disconnects, cancellation, completion, and upstream failures. Release per-connection resources and temporary trees promptly.
- For push sources that cannot slow down, use an explicit bounded overflow policy. Do not introduce an unbounded queue or silently discard events.
- Keep request/query context attached to the stream or send operation. Do not rely on a request thread's `ThreadLocal` remaining available across asynchronous callbacks.
- Keep blocking I/O off event-loop threads. Evaluate CPU scheduling and bounded offloading using benchmarks and each framework's execution model.
- Limit individual record/event size and JSON nesting. Memory use must depend on bounded in-flight payloads and buffers, not on the stream's lifetime.
- After headers are committed, an HTTP error status cannot replace the response. On serialization, parsing, limit, or evaluation failure, cancel upstream processing and terminate the stream by default. Never fall back to emitting the original unprojected JSON payload.
- Allow an explicitly configured SSE application error event when the application's protocol defines it. NDJSON does not acquire an invented error-record format by default.

### Streaming scope boundary

The first release supports independent NDJSON records and JSON-bearing SSE events. Ordinary finite JSON documents, including arrays, continue to use a full document tree.

Incremental projection of a single arbitrarily large JSON document, and incremental JSON-array wire encoding, are later execution strategies. Those require additional parser/framing and query-scope semantics; they can reuse the common query AST.

## 5. Implementation phases

### Phase 1 — Build foundation and adapter feasibility

Create the Gradle wrapper, settings, version catalog, convention plugins, initial CI, and minimum-runtime checks. Verify the proposed framework/plugin/toolchain combination before locking versions.

Prototype the response serialization hook for each framework, including one NDJSON record and one SSE event. Verify that the hook receives the complete payload, configured serializer, relevant declared type and serialization context, and per-connection query state before wire encoding.

Do not assume ordinary whole-response interception also intercepts every streaming send. Where a framework API bypasses that interception, provide an explicit Catnap send/emitter wrapper and document the supported integration path.

**Exit criteria:** minimal examples build for all target frameworks, streaming hook feasibility is established, and exact dependency versions are pinned.

**Completed:** the Gradle build, convention plugins, dependency locks, initial CI workflow, five framework examples, and Java 17/21/25 core checks are implemented. Each example passes finite JSON, single-record NDJSON, and single-event SSE checks against a real HTTP server. Explicit serialization/send boundaries preserve configured serialization, declared generic types, view context, and per-connection state. [Phase 1 findings](docs/PHASE_1.md) records the integration paths and remaining production work.

### Phase 2 — Query specification and conformance fixtures

Write the grammar and semantics for both dialect profiles. Specify whitespace, malformed expressions, wildcard scope, overlapping selectors, scalar behavior, heterogeneous arrays, empty selections, and missing fields.

Create fixtures containing input JSON, query dialect, expression, expected JSON or error, and source-position expectations for syntax errors. Include equivalent expressions in both dialects.

Add streaming fixtures that establish that queries are relative to each NDJSON record or SSE JSON payload, and that projection does not remove records/events.

**Exit criteria:** the supported language and behavior are reviewable independently of implementation.

**Completed:** [QUERY_SPECIFICATION.md](docs/QUERY_SPECIFICATION.md) defines both grammars, wildcard/overlap rules, scalar and array behavior, HTTP bypass handling, and UTF-16 syntax diagnostics. The [version 1 corpus](catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) contains 43 projection cases with 94 equivalent query variants, 63 syntax-error cases, 9 HTTP binding cases, and 8 logical streaming cases with 16 query variants. All 124 corpus integrity checks pass, and the resources are packaged in `catnap-testkit` for later conformance runners. `./gradlew check compatibilityTest` passes. These checks validate independently specified fixtures; execution against a production compiler/evaluator begins in Phase 3.

### Phase 3 — Core compiler and evaluator

Implement small recursive-descent parsers, a shared immutable query AST, deterministic selector merging, and the JSON tree interface. Implement projection independently of any framework or JSON library.

Provide structured syntax and evaluation errors, configurable limits, and optional bounded compilation caching. Cache keys include dialect, expression, and settings that affect compilation. Keep document values out of query plans and cache entries.

**Exit criteria:** core fixtures pass; compiled queries can be reused concurrently; evaluation preserves source trees; dependency checks confirm the intended core boundary.

**Completed:** `catnap-core` implements both recursive-descent dialect parsers, an immutable common selection AST, `JsonTreeAdapter<N>`, projection with source/result isolation, structured syntax/evaluation/limit errors, compilation/evaluation bounds, and an optional bounded LRU query cache. All 173 executable conformance cases pass, along with ownership, ordering, concurrency, cache, limit, maximum-depth, and malformed-input checks. `./gradlew build compatibilityTest` passes, running the core suite on Java 17, 21, and 25 and confirming zero third-party core runtime dependencies. The [core API guide](docs/CORE_API.md) documents usage, adapter responsibilities, initial limits, and cache behavior. Logical streaming tests exercise payload scope; production JSON bindings and transport adapters remain later phases.

### Phase 4 — JSON integrations and standalone usage

Implement Jackson 3, Jackson 2, and Kotlin serialization tree adapters. Preserve numerical precision, Unicode names, nulls, and JSON type distinctions without coercing every number through `double`.

Use the application's serializer configuration and relevant type/context information for object-to-tree conversion. Test custom serializers, naming strategies, ignored properties, views where supported, generic types, and Kotlin data classes.

Provide standalone examples for querying one document repeatedly and transforming an NDJSON source record by record. Avoid unnecessary JSON-string round trips where native tree or token APIs preserve the intended serialization behavior.

**Exit criteria:** JSON integrations pass the same expected-result fixtures, support standalone use, and satisfy the input/result ownership contract.

**Completed:** Jackson 2/3 and Kotlin serialization bindings execute all shared successful projection and logical-stream variants, with native writer/serializer tests for generic types, naming, ignored fields, views/attributes where supported, custom/contextual serializers, precision, and ownership. Kotlin encoding uses a precision-preserving serializer to avoid the pinned library's `Double` coercion. `catnap-streams` and the installed standalone application provide bounded record-at-a-time NDJSON processing, strict framing/UTF-8 checks, and verified early output. All 349 integration/stream/example tests pass, and `./gradlew build compatibilityTest` passes. See [JSON integrations](docs/JSON_INTEGRATIONS.md). The five build-logic plugins now each have a separate project and lockfile.

### Phase 5 — Finite-response framework adapters

Implement Spring Boot first, followed by Micronaut, Quarkus, and Ktor. Reuse the same core and JSON integration contracts.

| Adapter | Integration responsibilities |
|---|---|
| Spring Boot / MVC | Conditional auto-configuration, configuration properties, and JSON response-writing integration preserving the selected converter and serialization hints |
| Spring Boot / WebFlux | Equivalent configuration with reactive JSON encoding and lifecycle integration, isolated from the MVC artifact |
| Micronaut | Use its configured `JsonMapper`, native JSON tree, declared type metadata, and body-writing facilities |
| Quarkus | Register the runtime integration through a proper Quarkus REST extension and preserve configured JSON serialization |
| Ktor | Coordinate response projection with `ContentNegotiation`, serializer selection, type information, and route/application configuration |

Micronaut exposes tree conversion through [JsonMapper](https://micronaut-projects.github.io/micronaut-docs/latest/api/io/micronaut/json/JsonMapper.html); Ktor exposes [serialization integration points](https://ktor.io/docs/server-serialization.html). Validate the selected hooks against the pinned versions.

Expose consistent settings for the query parameter, dialect, endpoint enablement, limits, and query caching. Preserve status and content type, and recompute or remove body-dependent metadata such as content length and representation validators when necessary.

Requests without a query retain the native response path. Define eligible media types and response kinds explicitly so application errors, non-JSON bodies, and framework control responses are handled consistently.

**Exit criteria:** each example application passes the same finite-response contract using its native serialization configuration.

**Completed:** `catnap-http`, separate Spring MVC/WebFlux adapters and Boot starters, Micronaut, the Quarkus runtime/deployment extension, and Ktor implement the finite-response contract. All five examples pass the same Google/LinkedIn cases with configured serializers, generic types, naming/visibility rules, async results, precise numbers, errors, metadata, limits, and endpoint/global disablement. `./gradlew build compatibilityTest` passes.

Servlet tests verify deferred commitment and charset handling; WebFlux checks pooled-buffer release on success, failure, and cancellation. Micronaut passes the native-tree corpus and captures finite publishers under a total document limit with upstream cancellation. Quarkus retains its native endpoint writer settings and effective async generic types, runs existing writer interceptors once, and keeps augmentation dependencies out of its runtime. Ktor captures native byte-array/read-channel/write-channel bodies, preserves charset/Unicode, and verifies coroutine cleanup and bounded finite-producer processing. Because Ktor response headers are append-only, its documented `representationHeader` helper or `OutgoingContent.headers` keeps body-dependent metadata adjustable until projection.

Phase 6 below supplies production framework NDJSON/SSE integration and bounded Quarkus finite JSON-array publishers. See [Spring integration](docs/SPRING_INTEGRATION.md), [Micronaut integration](docs/MICRONAUT_INTEGRATION.md), [Quarkus integration](docs/QUARKUS_INTEGRATION.md), and [Ktor integration](docs/KTOR_INTEGRATION.md).

### Phase 6 — NDJSON and SSE adapters

Implement per-record and per-event projection for every first-release framework target. Use the serialization hooks established in Phase 1; keep protocol framing and transport lifecycles with the framework.

- Spring MVC: integrate per-send serialization for emitter and supported reactive-return paths. Provide explicit wrappers where needed to carry query state through asynchronous sends. Verify executor and emitter completion behavior.
- Spring WebFlux: transform or encode each item through the JSON integration while preserving reactive demand, order, cancellation, and SSE envelope metadata.
- Micronaut: project each supported publisher item or SSE event payload while preserving native serialization and stream lifecycle behavior.
- Quarkus: project each streamed item or SSE event's JSON data while preserving event metadata and configured JSON serialization. Its publisher path bypasses ordinary response filters; integrate finite JSON-array publishers with a total document byte bound as part of this work.
- Ktor: integrate with typed SSE serialization/send operations and NDJSON emission; retain suspending sends, coroutine cancellation, and configured serializer/type information.

The frameworks expose streaming facilities through [Spring asynchronous responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html), [Quarkus SSE support](https://quarkus.io/guides/rest/#server-sent-event-sse-support), and [Ktor SSE support](https://ktor.io/docs/server-server-sent-events.html). These APIs require dedicated streaming tests in addition to ordinary response tests.

Add NDJSON and SSE endpoints to each example. Include examples of custom serializers, heartbeat messages, event IDs, empty projections, and client cancellation.

**Exit criteria:** all first-release adapters pass the streaming contract, emit early items before source completion, preserve metadata/order/count, and demonstrate bounded buffering under slow consumers.

**Implemented:** shared connection-owned serialization bounds and explicit JSON/text payload markers live in `catnap-http`; `catnap-streams` adds an incremental UTF-8 NDJSON framer. Ktor implements typed and encoded NDJSON, Flow SSE, and native imperative SSE sends. It passes shared wire-level contracts for both dialects and real HTTP tests for early output, connection isolation, slow-consumer suspension, disconnect cleanup, and failures before/after commitment. Unit tests verify bounded handoff and rejected concurrent push sends. Native serializers and SSE framing remain application/framework-owned. See [Ktor integration](docs/KTOR_INTEGRATION.md) for supported APIs, header handling, bounds, and optional application error events.

Spring WebFlux implements typed/encoded NDJSON and native SSE response helpers for controllers and explicit functional routes. The shared wire/lifecycle contracts verify native writer configuration, both dialects, early output, failure timing, connection isolation, slow consumers, and disconnect cleanup. Its no-prefetch handoff requests another record only when the current group is consumed, with explicit push-overflow rejection covered separately. `catnap-testkit` now packages reusable Java HTTP lifecycle assertions and example source gates for the remaining adapters. See [Spring integration](docs/SPRING_INTEGRATION.md) for supported response APIs and serialization context.

Spring MVC implements bounded native emitter wrappers for imperative sends, Flow/Reactive Streams publishers, and owned NDJSON input streams. Real Tomcat tests pass the shared streaming contracts, slow-consumer/disconnect checks, async controller initialization, native timeout cancellation, and failures before/after commitment. Unit tests verify pending-send/executor limits, one-item demand, and owned-input cleanup. The full build and Java compatibility matrix pass. See [Spring integration](docs/SPRING_INTEGRATION.md) for worker/queue configuration and supported APIs.

Micronaut implements typed/encoded NDJSON and native SSE response helpers with explicit `JsonMapper`/`Argument` or serializer-callback context. Shared real-Netty wire/lifecycle tests cover both dialects, early delivery, connection isolation, bounded slow-consumer production, disconnect cancellation, and pre/post-commit failures. Optional SSE protocol error events are bounded and terminate the response. Unit tests verify bounded one-item prefetch, nonblocking native write handoff, incremental chunk processing, Reactor context, single-subscription ownership, push-overflow cleanup, disabled-projection bounds, and error-event/cancellation policy. All 123 adapter tests, 55 example tests, and the full `build compatibilityTest` pass. See [Micronaut integration](docs/MICRONAUT_INTEGRATION.md).

Quarkus now supplies `CatnapQuarkusResponses.jsonArray` for explicit finite `Multi`/Flow publishers. It serializes into one total-document byte bound, requests one item at a time, projects the completed array once, and returns through the native byte-array response path. Tests verify configured generic/view serialization, both dialects, byte-bound cancellation, real client disconnection before commitment, and stack safety for 50,000 synchronous items. The full build and Java compatibility checks pass. See [Quarkus integration](docs/QUARKUS_INTEGRATION.md).

Quarkus also implements typed/encoded NDJSON and JSON-bearing SSE through the explicit `CatnapStream` resource return type. Its native REST handler prepares the first payload before commitment, permits one outstanding native write, retains endpoint status/header customizers, and propagates disconnects and source failures. Standard SSE fields use native formatting, with a documented correction for the pinned writer's multiline comment/line-ending behavior. All 13 runtime tests and 62 Quarkus HTTP/probe tests pass, including the shared wire/lifecycle contracts, native write acknowledgment ordering, push-overflow rejection, and optional bounded protocol error events.

**Completed:** all five framework targets pass the same streaming wire/lifecycle contracts for both query dialects. They retain native serialization context, preserve event/record shapes and metadata, emit early items while the source is open, cancel on disconnect, and demonstrate bounded production under slow consumers. The full `build compatibilityTest` passes. Phase 7 will measure sustained throughput/allocation and retained memory over longer streams.

### Phase 7 — Performance, publication, and release documentation

Benchmark query compilation, tree construction, projection of existing trees, serialization, and complete response processing separately. Include small documents, large arrays, deep nesting, sparse selections, and wildcards.

For streams, measure latency to the first projected item, sustained throughput, allocation per item, and memory behavior over increasing stream durations at a fixed payload size and bounded concurrency. Compare with ordinary framework serialization. Establish practical defaults from measurements; do not claim hard real-time latency guarantees.

Publish artifacts to a temporary local Maven repository during verification, then build independent consumers from those artifacts. Verify POM metadata, Gradle metadata, runtime dependencies, source/documentation artifacts, and BOM alignment. Configure signed release publication separately from normal development builds.

Document the standalone API, both dialects, adapter configuration, finite/NDJSON/SSE behavior, supported framework/serializer combinations, failure policies, minimum Java versions, and migration from the original Catnap API. Include a guide for adding a JSON integration or framework adapter.

**Exit criteria:** the full test matrix, publication smoke tests, and supported native-image checks pass; benchmark results and compatibility documentation accompany the release candidate.

[Native verification](docs/NATIVE_IMAGES.md) now passes all 62 Quarkus and 55 Micronaut example tests on GraalVM 25.0.4/macOS AArch64. Quarkus reuses the JVM contracts against a separate native server, with HTTP source controls replacing shared-memory test gates. Micronaut compiles its existing application and tests into one executable. The example serialization probe explicitly registers DTOs hidden behind manual Jackson serialization. Logs, JUnit reports, compiler details, executable hashes, and source identity are archived. A separate manual CI job covers both frameworks; hosted Linux execution remains unverified.

**Implemented:** `catnap-bom` aligns all 16 published libraries without importing framework BOMs. Java artifacts include Javadoc; Kotlin artifacts include Dokka API documentation; dependency-only starters include usage documentation. `publicationSmokeTest` publishes to a fresh temporary Maven repository and builds independent copies of the standalone and all five framework examples twice: Gradle metadata, then Maven POMs with Gradle redirection disabled. Both modes pass finite/streaming contracts, binding checks, runtime dependency assertions, Quarkus augmentation, artifact metadata/source/documentation inspection, and published-core execution on Java 17/21/25. See [publication verification](docs/PUBLICATION.md), [migration](docs/MIGRATION.md), and [extension guidance](docs/EXTENDING.md). Local publication does not upload a public release. Full-duration streaming measurements, native results, and compatibility documentation are recorded below. The [requirement audit](docs/RELEASE_AUDIT.md) records completion within the documented local verification scope, including the historical WebFlux timeout and subsequent passing diagnostics.

The HTTP measurement harness runs real dedicated servers for all five frameworks and passes its complete smoke matrix. [HTTP measurements](docs/benchmarks/2026-11-16/http/README.md) preserve 40 throughput/latency cases and 20 retained-memory cases across all five frameworks, with 30 latency samples per path and 5/20/60-second live-connection checkpoints. The [Micronaut investigation](docs/benchmarks/2026-11-16/http/micronaut-reentrancy/README.md) isolates the synchronous-source/cleanup interaction and verifies a one-item asynchronous handoff across all six Catnap stream modes; the refreshed native suite passes all 55 tests and full-duration measurements pass all 12 cases. The [WebFlux investigation](docs/benchmarks/2026-11-16/http/webflux-drain-audit/README.md) preserves the original timeout, which recorded no delivered count, and the passing repeated-boundary, held-reader, synchronous-source, and original-source replay runs. Its cause remains unknown; no WebFlux production fix is claimed. The `benchmark-server` convention is a sixth independent build-logic project, following the same ownership and locking rules as the original five. The [compatibility matrix](docs/COMPATIBILITY.md) records exact tested versions and the verified native-image scope.

**Completed:** the [final local verification](docs/verification/2026-11-16/release-candidate/README.md) passes the full build, compatibility tests, independent consumers, and streaming smoke suites. Native and benchmark evidence accompanies the candidate. The original Phase 7 exit criteria are met without relaxing any count assertion, drain deadline, or retention threshold. The failed historical benchmark attempt remains archived as an unexplained observation.

## 6. Verification strategy

The [November 16, 2026 performance baseline](docs/benchmarks/2026-11-16/README.md) records 56 JMH cases with two forks and ten measured samples per case: both query dialects, cache hits, tree construction, existing-tree projection, serialization, complete bounded response processing, and equal-output stream-record preparation. Raw results, environment/workload sizes, and measured-source identity accompany the report. See [performance and operating bounds](docs/PERFORMANCE.md). The demand-aware streaming fixture and measurement controls pass nine infrastructure tests; the incremental HTTP client passes five protocol-reader tests. Dedicated servers and the smoke runner pass native/bypass/full/sparse NDJSON and SSE paths for all five frameworks, including different queries over the same publisher, disconnect cleanup, and observed-GC sampling mechanics. The separately archived full-duration HTTP runs contain the actual measurement evidence; smoke timings are not performance results. CI runs the smoke suite and independent publication consumers and offers JMH and HTTP measurements as separate manual jobs.

### Core and JSON integrations

- Fixture-based parser and evaluator tests with independently specified expected results.
- Equivalent Google/LinkedIn selections, overlapping selectors, nested arrays, missing fields, nulls, precision, and Unicode.
- Source preservation, result mutation isolation, concurrent compiled-query reuse, and bounded cache behavior.
- Generated/fuzzed malformed and deeply nested queries to verify termination, diagnostics, and limits.
- Dependency-boundary and advertised minimum-runtime checks.

### Framework and streaming contracts

- Native serializers and type context are preserved for both finite and streaming responses.
- Missing queries preserve normal response behavior; invalid queries fail before response commitment.
- NDJSON record boundaries survive split byte buffers, CRLF, multiple records per chunk, and split UTF-8 sequences where encoded NDJSON is accepted.
- SSE IDs, event names, retry information, comments, heartbeat messages, and payload encoding survive projection.
- Empty projections still emit a record/event. Intentional text events and malformed JSON events follow distinct documented policies.
- First output arrives while the source remains open. No implementation waits for an infinite source to complete.
- Slow-consumer tests verify bounded demand/prefetch/queues. Longer test streams at a fixed item size do not cause retained memory to grow with total item count.
- Cancellation/disconnection releases resources and cancels upstream work. Error tests cover failures before and after response commitment.
- Query and payload state remain isolated across concurrent subscribers, including subscribers with different selections from the same source.

Run core tests on Java 17, 21, and 25. Run each adapter on its documented supported runtimes. Keep native-image verification for adapters where support is claimed in a dedicated release job, using framework-supported toolchains.

## 7. Planned build tasks and release gate

```bash
# Formatting, current tests, HTTP feasibility probes, and bytecode/dependency checks
./gradlew check

# Focused core tests
./gradlew :catnap-core:test

# Query/stream fixture integrity (core:test executes query conformance)
./gradlew :catnap-testkit:test

# Dedicated aggregate of NDJSON and SSE integration tests
./gradlew streamingTest

# Core execution on Java 17, 21, and 25
./gradlew compatibilityTest

# Forked CPU/allocation measurements outside ordinary verification
./gradlew :benchmarks:jmh

# Validate the HTTP measurement harness, then collect sustained streaming measurements
./gradlew :benchmarks:streaming-runner:smoke
./gradlew :benchmarks:streaming-runner:measure

# Publish and build independent consumers using Gradle metadata and Maven POMs
./gradlew publicationSmokeTest
```

The Gradle build, `check`, `streamingTest`, `compatibilityTest`, `publicationSmokeTest`, and `:benchmarks:jmh` exist. Current HTTP tests cover the finite-response and production streaming wire/lifecycle contracts for all five frameworks, alongside the isolated Phase 1 feasibility probes. The separate `streamingTest` aggregate selects the same framework test tasks without duplicating test implementation. Publication consumers resolve Catnap only from the temporary Maven repository, without project substitution. Timed JMH and HTTP measurements and native-image jobs remain separate from ordinary checks. Their local evidence and the completed requirement audit are recorded. The historical WebFlux drain timeout remains documented with its diagnostic limitations and passing follow-up runs.

The first release is ready when standalone JSON projection, both dialects, all named framework adapters, NDJSON, and JSON-bearing SSE are implemented and documented; conformance and lifecycle tests pass; buffering is bounded; and published artifacts work in independent consumers.
