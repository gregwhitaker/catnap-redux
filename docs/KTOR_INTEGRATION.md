# Ktor integration

`catnap-ktor` provides the `Catnap` plugin for Ktor 3.6.1 on Java 17. It runs after the application's selected `ContentNegotiation` converter has rendered a response, and before content encoding and transport commitment. JSON conversion retains the application's serializers, naming rules, contextual serializers, and declared generic type.

## Installation

Inside this repository:

```kotlin
implementation(project(":catnap-ktor"))
```

The publication coordinate is `com.github.gregwhitaker:catnap-ktor:3.0.0-SNAPSHOT`. Independent Gradle metadata and Maven POM consumers verify the published artifact locally. Follow the [installation instructions](../README.md#installation) to build local artifacts, or the [publication guide](PUBLICATION.md) to consume a version available in GitHub Packages.

Install the application's serializer normally, then install Catnap on the routing root:

```kotlin
install(ContentNegotiation) { json(applicationJson) }
routing {
    install(Catnap)
    get("/products") { call.respond(products) }
    route("/unprojected") {
        install(Catnap) { enabled = false }
        get { call.respond(products) }
    }
}
```

A query such as `?fields=name,price(amount)` selects fields from the resulting JSON. No DTO wrapper is required. Catnap also handles JSON `ByteArrayContent`, `ReadChannelContent`, and suspending `WriteChannelContent` responses.

The plugin can be installed on the application if route overrides are unnecessary. Ktor does not allow the same route-scoped plugin to be installed on both an application and a route. Use the routing root for a common configuration with nested overrides. The closest route installation supplies its complete configuration; parent DSL settings are not implicitly merged into a child installation.

## Configuration

The native `catnap` configuration section and the installation DSL expose the same settings as the other adapters:

| Configuration property | DSL property | Default |
|---|---|---|
| `catnap.enabled` | `enabled` | `true` |
| `catnap.parameter` | `parameter` | `fields` |
| `catnap.dialect` | `dialect` | `CATNAP` |
| `catnap.max-query-length` | `maxQueryLength` | `16384` UTF-16 units |
| `catnap.max-query-depth` | `maxQueryDepth` | `64` |
| `catnap.max-selectors` | `maxSelectors` | `4096` |
| `catnap.max-json-depth` | `maxJsonDepth` | `128` |
| `catnap.max-work` | `maxWork` | `1000000` |
| `catnap.cache-capacity` | `cacheCapacity` | `256`; zero disables caching |
| `catnap.max-payload-bytes` | `maxPayloadBytes` | `1048576` |

The default `CATNAP` dialect supports [RSQL-subset array predicates](QUERY_SPECIFICATION.md#catnap-array-predicates), such as `payload(amount)[display_name==ACME]` on the `/catnap/catalog` example. Set `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` to use either field-selection profile without predicates. Programmatic configuration can likewise set `dialect = QueryDialect.GOOGLE` or `dialect = QueryDialect.LINKEDIN`.

For example:

```kotlin
install(Catnap) {
    dialect = QueryDialect.LINKEDIN
    parameter = "select"
    maxPayloadBytes = 2 * 1024 * 1024
}
```

Use `?select=payload:(amount)`, appropriately URL-encoded. An optional `service` property accepts a shared `ProjectionService`; its immutable settings override the individual DSL properties and allow multiple installations to share a bounded query cache. Invalid settings fail during installation.

## Response metadata

Ktor's `ResponseHeaders` API is append-only: it has no supported operation to remove a header once `call.response.headers.append` has stored it in an engine response. Body-dependent metadata therefore needs to remain adjustable until projection eligibility is known.

Attach representation metadata to `OutgoingContent.headers`, or use Catnap's deferred helper:

```kotlin
call.response.headers.append("X-Application", "preserved")
call.representationHeader("ETag", originalEntityTag)
call.representationHeader("Content-Digest", originalDigest)
call.respond(value)
```

`representationHeader` accepts only body-dependent headers: content length, representation validators/digests, and range metadata. On a projected response these original values are discarded and content length is recalculated. On an unprojected response, including missing/blank queries and ineligible media/status, the original metadata is preserved. Ordinary unrelated headers continue using Ktor's native header API.

If a projection encounters body-dependent headers already appended directly to the engine response, it fails before writing the projected body. It does not use reflection to mutate private engine state or send a projected body with stale validators. Applications using such headers must move them to the helper or outgoing content. Native middleware that makes conditional-response decisions before Catnap must account for the query as part of the representation's identity.

Outgoing content headers and trailers retain unrelated values. Original version metadata is discarded; other outgoing-content extension properties remain available to later plugins.

## Bounds, failures, and cancellation

Queries bind to call attributes before the endpoint executes. Duplicate parameters, syntax errors, and compilation limits produce HTTP 400 problem JSON. Route/global disablement bypasses parsing. Missing or ASCII-blank queries retain the native response body without capture or tree construction.

Successful `application/json` and `application/*+json` responses are eligible. Errors, 204/205/206, HEAD, no-body responses, non-JSON bodies, and bodies already carrying a non-identity content encoding remain native. Projection occurs before Ktor's content-encoding phase.

Finite channel bodies are consumed under the total document byte limit. Writer coroutines run within the current call; Catnap launches no detached producer and does not collect domain objects into an unbounded list. Read channels are cancelled on completion, failure, or cancellation. Writer errors and cancellation propagate without emitting a partial original document. A byte-array response is already allocated by the native converter before Catnap sees it; the projection limit does not limit allocations performed by that converter or application code.

Parsing retains numeric lexemes and validates scalars even in fields omitted by the query. A string-aware nesting check bounds parser recursion before tree construction. Output uses the precision-preserving Kotlin serialization tree encoder. Strict charset decoding rejects malformed bytes, and Unicode escapes preserve characters that cannot be represented directly by the response charset.

Serialization, malformed JSON, byte/depth/work-limit, or evaluation failures produce a server error before commitment with no fallback to the original payload. Coroutine cancellation propagates as cancellation.

## NDJSON and SSE

Streaming uses explicit response/send helpers. Construct a new encoder for each call, supplying the application's `Json` instance and the complete serializer, including generic/contextual type information. The encoder captures the already-validated connection query. It must not be shared between connections.

```kotlin
get("/quotes.ndjson") {
    val encoder = call.jsonStreamEncoder(
        applicationJson, Envelope.serializer(Quote.serializer())
    )
    call.respondNdjson(quotes, encoder) // quotes: Flow<Envelope<Quote>>
}
```

`respondNdjson` emits each value as compact JSON plus LF and flushes each record. Empty projections remain records. Arrays, scalars, and null retain their shape. It never collects the stream into a document. `respondEncodedNdjson(Flow<ByteArray>, encoder)` instead accepts arbitrary UTF-8 transport chunks: framing handles split codepoints, split records, multiple records per chunk, and LF/CRLF. Blank records, bare CR, invalid UTF-8, malformed JSON, oversized records, and an unterminated nonempty tail fail the stream. Explicit encoded JSON is validated even without a query.

Install Ktor's `SSE` plugin for event responses. Use native `TypedServerSentEvent` envelopes with `StreamPayload.json(value)`, `StreamPayload.encodedJson(text)`, or `StreamPayload.text(text)` to distinguish JSON from intentional text. No payload (`data = null`) is a control event; `StreamPayload.json(null)` with a nullable serializer is a JSON null payload.

```kotlin
install(SSE)
// Inside routing { install(Catnap) ... }
get("/quotes.events") {
    val encoder = call.jsonStreamEncoder(
        applicationJson, Envelope.serializer(Quote.serializer())
    )
    val events = quotes.map { quote ->
        TypedServerSentEvent(
            data = StreamPayload.json(quote),
            event = "quote", id = quote.payload.id, retry = 1500
        )
    }
    call.respondSse(events, encoder)
}
```

Ktor performs event framing and flushing. Catnap preserves IDs (including empty IDs), names, retry values, comments, multiline text, and heartbeat/control events. Text that happens to look like JSON remains text. JSON serialization uses the supplied configuration before querying the actual JSON field names.

Both Flow response helpers serialize the first item before committing headers. Queries fail with HTTP 400 before the endpoint runs; first-item source/serialization/projection failures produce a server error. After commitment, failures terminate the stream and cancel its producer. Public failure messages do not include parser input. No failed item is replaced with its unprojected value.

An SSE application may explicitly provide `onError = { failure -> ServerSentEvent("temporary failure", event = "application-error") }`. After a committed-stream failure, Catnap attempts this one application-defined protocol event and then ends the stream. This event is byte-bounded, is not projected, and must contain only data intended for clients. The default is termination without an error event. First-item failures still produce an HTTP error; coroutine cancellation is never recovered.

For imperative producers, use `call.respondSse(encoder) { send(TypedServerSentEvent(...)) }`. This opens a native session after query validation and representation-header adjustment; payload encoding occurs during each suspending send. First-send failures therefore terminate an already-committed response. `ServerSSESession.projecting(encoder)` also wraps an existing native session, but cannot adjust headers already committed by the native SSE route. Prefer the response helper when using representation headers.

Imperative sends admit one caller at a time. A concurrent send is rejected immediately with an exception; it is not queued or silently discarded, and the accepted send can finish. An application with multiple push producers must serialize them through its own explicitly bounded queue and handle rejection. Payload/native-send failure closes the wrapper and native session; later sends fail.

## Streaming bounds and lifecycle

The Flow adapter uses a rendezvous channel in the call's coroutine scope: at most one encoded item is being sent and one prepared item is waiting. Native transport/socket buffers have their own bounds. Consumer suspension stops source production, disconnects cancel the producer, and cleanup joins that producer before returning. There is no detached coroutine, unbounded queue, or retained per-item global state.

`maxPayloadBytes` bounds native serialized data before tree construction and bounds projected output. SSE envelope fields have a separate combined bound of the same size. Native SSE framing can add bytes beyond these data/envelope bounds. Typed no-query responses skip the extra JSON tree; the explicit helpers still enforce byte limits. Declared encoded JSON always validates framing, UTF-8, syntax, and depth. Active queries also enforce the configured depth and evaluation-work limits. Application allocations and incoming chunk sizes are outside Catnap's control; encoded-input producers should use bounded chunks.

Create the encoder from a UTF-8 serializer callback if the application uses another native serializer:

```kotlin
val encoder = call.jsonStreamEncoder<MyType> { value, output ->
    configuredWriter.writeValue(output, value)
}
```

The callback receives a bounded output stream. It runs in the call's coroutine context and must not perform blocking I/O on an event-loop dispatcher. Catnap does not introduce another global executor. For stream representation headers, use `representationHeader` before responding; original validators are discarded only when projection is active.

## Example and verification

```bash
./gradlew :examples:ktor:run
curl --get http://localhost:8080/catnap/quote \
  --data-urlencode 'fields=payload(amount)'
./gradlew :catnap-ktor:check :examples:ktor:test
```

The result is `{"payload":{"amount":"EUR:12.50"}}`. The example uses generic Kotlin envelopes, configured snake-case naming, transient properties, and contextual money/decimal serializers.

The example additionally exposes `/catnap/stream/ndjson`, `/catnap/stream/sse`, `/catnap/stream/send`, and `/catnap/stream/encoded`. For example:

```bash
curl --no-buffer --get http://localhost:8080/catnap/stream/sse \
  --data-urlencode 'fields=payload(amount)'
```

Tests exercise the shared finite and streaming HTTP contracts for the configured dialects, native generic/contextual serializers, text/control events, record/event counts, early delivery, concurrent connection isolation, and errors before and after commitment. A slow real HTTP client stops source production and disconnect cancels that source. Unit tests deterministically verify the two-item adapter bound, producer cleanup, immediate concurrent-send rejection, and closed-session behavior. Encoded-input checks cover split Unicode, CRLF, invalid framing/UTF-8/JSON, and byte/depth limits. A 10,000-item finite producer stops within five items under a 4 KiB total document limit.

An unbounded source must use a streaming response mode rather than ordinary finite JSON.
