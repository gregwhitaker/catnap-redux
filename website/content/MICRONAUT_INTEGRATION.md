# Micronaut integration

The [Micronaut catalog walkthrough](examples/micronaut/README.md) covers product detail, paginated search, and image/variant filtering with seeded data, generated Serde models, and real HTTP tests.

`catnap-micronaut` integrates with Micronaut's configured response writers and `io.micronaut.json.tree.JsonNode`. The verified baseline is Micronaut platform 5.2.6, core 5.2.23, and Micronaut Serialization 3.2.6 with its Jackson-core backend. This module requires Java 25, as Micronaut 5 does. The framework-independent core still targets Java 17.

## Installation and endpoints

Configure a repository using the [installation guide](INSTALLATION.md), then add the adapter:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0"))
    implementation("com.github.gregwhitaker:catnap-micronaut")
}
```

Keep the application's normal Micronaut and serialization configuration. Catnap uses those serializers when constructing the JSON available for selection.

Micronaut discovers the adapter's compiled bean and filter metadata. Finite responses need no controller wrapper or manual evaluator call. Streaming responses use the explicit helpers below. Start the example:

```bash
./gradlew :examples:micronaut:run
curl --get http://localhost:8080/catnap/quote \
  --data-urlencode 'fields=payload(amount)'
```

The result is `{"payload":{"amount":"EUR:12.50"}}`. The example uses Micronaut Serialization's configured snake-case naming, public JSON view, and an injected custom money serializer. `List<T>`, `Mono<T>`, and finite publishers are exercised alongside ordinary return values and `HttpResponse<T>`.

JSON views follow the application's native configuration. The example enables them with `micronaut.serde.json-view-enabled=true`; Catnap does not silently enable a different serialization policy. `micronaut.serde.property-naming-strategy=SNAKE_CASE` provides the example's naming policy.

## Configuration

The immutable `CatnapConfiguration` bean maps the same property names and defaults as Spring:

| Property | Default |
|---|---|
| `catnap.enabled` | `true` |
| `catnap.parameter` | `fields` |
| `catnap.dialect` | `CATNAP` |
| `catnap.max-query-length` | `16384` UTF-16 units |
| `catnap.max-query-depth` | `64` |
| `catnap.max-selectors` | `4096` |
| `catnap.max-json-depth` | `128` |
| `catnap.max-work` | `1000000` |
| `catnap.cache-capacity` | `256` entries; zero disables caching |
| `catnap.max-payload-bytes` | `1048576` |

The default `CATNAP` dialect supports [RSQL-subset array predicates](QUERY_SPECIFICATION.md#catnap-array-predicates), such as `images(url)[size==medium]` on `/api/products/trail-pack-28`. Set `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` to use either field-selection profile without predicates.

For LinkedIn syntax and a different parameter:

```properties
catnap.dialect=LINKEDIN
catnap.parameter=select
```

Then use `?select=payload:(amount)`, appropriately URL-encoded. Invalid settings fail when Micronaut creates the projection service. A custom `ProjectionService` bean overrides the default service and cache. `@CatnapProjection(enabled = false)` opts out a controller or method; global disablement bypasses query parsing as well.

## Serialization and failure behavior

A request filter validates and compiles the query before route execution. Request attributes carry the immutable query state across asynchronous callbacks. No thread-local request context is required. Missing or ASCII-blank queries keep the native response writer and avoid an additional tree or body capture.

For an eligible finite body, a response filter wraps the writer already selected by Micronaut. It retains the declared `Argument<T>` and writer specialization, captures the writer's actual JSON within the byte limit, builds a native Micronaut tree, projects it, and emits the selected JSON. It does not install a different application `JsonMapper` or substitute Jackson databind. The native tree codec uses arbitrary-precision decimal and integer parsing so the extra materialization step cannot round numbers.

Successful `application/json` and `application/*+json` responses are eligible. Errors, 204/205/206 responses, non-JSON bodies, and responses already marked with a non-identity content encoding keep their native path. Projected `{}` values retain the successful status. The adapter preserves content type and unrelated headers, removes original representation validators/range metadata, and supplies the new content length. Native transport compression runs after projection.

Duplicate parameters, invalid syntax, and compilation bounds produce HTTP 400 problem JSON with a stable error code. Server-side serialization, parse, byte/depth/work-limit, or projection failures produce HTTP 500 before response commitment, with no fallback to the original body. Outbound failures raised inside a reactive response filter are explicitly classified as server errors; Micronaut's ordinary codec exception handler otherwise treats some of them as malformed client input.

## Finite publishers

A publisher producing ordinary JSON represents one complete finite document. The adapter serializes source items in order using the native selected writer, preserves Micronaut's array-versus-raw-fragment framing policy, and accumulates the encoded document under `max-payload-bytes`. After completion it evaluates once over the whole tree. An empty object publisher produces `[]`.

The accumulation stores encoded bytes rather than a list of domain objects. Encoding uses one item at a time with prefetch one. Writers marked blocking run on Micronaut's managed blocking executor. Source failures, downstream cancellation, or the total document limit stop processing and cancel upstream. The limit applies before the server commits the original document; it does not constrain objects the application has already allocated.

An unbounded source belongs on NDJSON or SSE. Streaming examples use `/catnap/stream/*`.

## NDJSON and SSE responses

Inject the singleton `CatnapMicronautStreams` bean and return its response from a controller. Each call creates an encoder with the application's configured `JsonMapper` and declared `Argument<T>`:

```java
@Get("/quotes.ndjson")
MutableHttpResponse<?> quotes(HttpRequest<?> request) {
  JsonMapper configured = mapper.cloneWithViewClass(Public.class);
  Argument<Envelope<Quote>> type = (Argument) Argument.of(Envelope.class, Quote.class);
  var encoder = streams.encoder(request, configured, type);
  return streams.ndjson(request, quotePublisher(), encoder);
}
```

The mapper retains injected serializers and naming configuration. Supply the view and full generic `Argument` explicitly; a controller's `@JsonView` does not configure a callback inside its method. Alternatively, `encoder(request, (value, output) -> ...)` accepts any native serializer operation that writes one UTF-8 JSON value. Both overloads enforce the byte limit during serialization, before a tree is allocated. Active projection uses native Micronaut nodes and precise number parsing. The query is fixed for the connection.

`ndjson` emits one compact JSON value followed by LF per source item. It preserves root objects, arrays, scalars, and JSON null; empty selections remain `{}` records. Reactive Streams forbids Java null source items, so use a JSON null node or a non-null wrapper whose serializer emits null. A finite empty source emits an empty NDJSON body.

`encodedNdjson(request, chunks, encoder)` accepts a `Publisher<byte[]>` with arbitrary UTF-8 chunk boundaries. Records may span chunks, including split multibyte characters, and a chunk may contain many records. The incremental framer processes only demanded records; it does not collect all records from a chunk. LF/CRLF are accepted. Blank records, bare CR, invalid UTF-8/JSON, byte/depth limits, and nonempty unterminated tails fail. Already-encoded JSON is always validated even without a query. Producers must bound their own input chunks.

For SSE, use native Micronaut `Event` envelopes and explicit payload markers:

```java
Flux<Event<StreamPayload<Envelope<Quote>>>> events = quotes.map(quote ->
    Event.of(StreamPayload.json(quote)).id("42").name("quote")
        .retry(Duration.ofMillis(1500)).comment("heartbeat"));
return streams.sse(request, events, encoder);
```

`StreamPayload.json(value)` serializes typed JSON; `encodedJson(text)` declares complete raw JSON and always validates it; `text(text)` preserves intentional text even when it resembles JSON. `StreamPayload.json(null)` is JSON null data. A heartbeat can use `Event.of(StreamPayload.<Quote>text("")).comment("heartbeat")`: native Micronaut framing emits no data line for empty text. The helper also accepts null event data via Micronaut's `Event.of(existingEvent, null)` overload and treats it as a control event. Event metadata and multiline text/comments stay with the native SSE writer; Catnap does not rewrite wire fragments.

The response helpers supply their media types. Add application headers to the returned mutable response. With an active query, the response filter removes original content length, validators, digests, and range metadata before native commitment; unrelated headers remain. Endpoint/global disablement and missing/blank queries skip additional tree construction for typed payloads. The stream bean remains available when projection is disabled, and explicit helpers retain payload/framing bounds. Bare publisher responses do not automatically acquire per-item projection: use these helpers.

## Streaming demand, failure, and ownership

The helper hands prepared records/events to the native body writer through Reactor's shared parallel scheduler with prefetch one. It can retain one prepared item in addition to the item currently being delivered. This lets Micronaut finish its byte-consumption callback before a synchronous source delivers more data. Without that boundary, the pinned Micronaut Netty implementation can keep requesting data while its byte accounting is deferred and exhaust direct memory. Encoded NDJSON still frames chunks incrementally with zero source prefetch before this final one-item handoff. Real HTTP tests verify bounded production while a client stops reading and upstream cleanup on disconnect. Native transport buffers, preallocated domain objects, and application source queues have their own bounds.

One response owns one encoder and permits one subscription. Build a new response/encoder for another connection; share only the immutable compiled query through the service's bounded cache. Reactor context is preserved through the helper. Serialization can run on the subscribing thread or the shared parallel worker; the handoff is not a blocking-work executor. Offload blocking sources through an application-owned bounded scheduler or Micronaut's managed executor. For push producers, choose an explicit bounded overflow policy, for example `Flux.create(..., FluxSink.OverflowStrategy.ERROR)`, and register cleanup with `onDispose`. Overflow stops the producer immediately; an already prepared item precedes the terminal error when the consumer requests it. There is no silent event-drop policy.

`max-payload-bytes` bounds serialized input and selected output for each item. Combined SSE metadata has a separate bound of the same size; native framing adds bytes. JSON nesting and active-query evaluation bounds apply per payload. No prior item tree is retained by the helper.

Invalid queries fail with HTTP 400 before source subscription. First-item serialization, parsing, size, or evaluation failures use HTTP 500 with a generic client message. After output starts, failures cancel upstream and terminate the stream. Native transport failures follow Micronaut's cleanup path. The original unprojected payload is never used as an error fallback, and cancellation is not recovered.

If the application's SSE protocol defines an error event, supply a final `Function<Throwable, Event<String>>` to `sse`. After the helper has successfully encoded and forwarded an event, it can append one bounded, unprojected text/control event and complete. First-item failures retain the HTTP error path. For example, `failure -> Event.of("temporary failure").name("application-error")`. The callback must return client-safe data. A callback failure or oversized error event terminates the stream; errors from the native transport itself are not converted to events. NDJSON has no implicit error-record format.

The helper uses Micronaut's [native event API](https://docs.micronaut.io/5.2.x/http/) and response-body writer lifecycle; the pinned implementation is also checked against its source during integration work.

## Native tree use

```java
JsonNode tree = mapper.writeValueToTree(declaredArgument, value);
JsonNode selected = Catnap.compile("name,price(amount)", QueryDialect.CATNAP)
    .evaluate(tree, MicronautTreeAdapter.INSTANCE);
```

`MicronautJson` provides precise single-value parsing and bounded output encoding for native trees. `MicronautTreeAdapter` creates fresh result containers and snapshots unusual mutable number implementations accepted by Micronaut's generic node factory. The source remains independent of the result.

## Verification

```bash
./gradlew :catnap-micronaut:check :examples:micronaut:test
```

Tests run the shared projection and logical-stream conformance cases against the native tree adapter. Additional checks cover precision, ownership, malformed/deep JSON, the default CATNAP dialect and explicit dialect overrides, custom serialization, JSON views, ignored fields, missing-query bypass, disablement, headers, and limits. A finite-publisher test verifies that a 4 KiB cap cancels a 10,000-item source after at most five items and returns a server error without exposing the original payload.

Production streaming tests run the shared wire contract for the configured dialects and the real Netty lifecycle contract: early output, per-connection queries, slow consumers, disconnects, failures before/after commitment, and optional protocol error events. Tests also cover configured generic/view serialization, raw NDJSON boundaries and UTF-8, metadata/control/text events, header invalidation, malformed declared JSON, payload/metadata bounds, one-item prefetch, nonblocking demand handoff, cancellation, Reactor context, single-subscription ownership, and push-overflow cleanup.
