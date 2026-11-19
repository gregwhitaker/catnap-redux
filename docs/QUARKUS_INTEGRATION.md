# Quarkus integration

The [Quarkus catalog walkthrough](../examples/quarkus/README.md) covers product detail, paginated search, and image/variant filtering with seeded data, CDI services, Mutiny responses, and real HTTP tests.

`catnap-quarkus` is a Quarkus REST extension for JSON projection. Its runtime and augmentation code are separate Gradle projects in `catnap-quarkus/runtime` and `catnap-quarkus/deployment`. The verified framework baseline is Quarkus 3.40.1.2 with Jackson 2.21.8 and Java 17.

## Installation and endpoints

Inside this repository:

```kotlin
implementation(project(":catnap-quarkus"))
```

The publication coordinates are `com.github.gregwhitaker:catnap-quarkus:3.0.0-SNAPSHOT` and `com.github.gregwhitaker:catnap-quarkus-deployment:3.0.0-SNAPSHOT`. Applications depend on the runtime artifact; its extension descriptor identifies the augmentation artifact. Independent Gradle metadata and Maven POM consumers verify both artifacts and their registration metadata. Follow the [installation instructions](../README.md#installation) to build local artifacts, or the [publication guide](PUBLICATION.md) to consume a version available in GitHub Packages.

The extension registers request and response filters during augmentation. Resource methods return ordinary objects, lists, `Response` values, or asynchronous single results such as `Uni<T>`. Start the example:

```bash
./gradlew :examples:quarkus:quarkusDev
curl --get http://localhost:8080/catnap/quote \
  --data-urlencode 'fields=payload(amount)'
```

The result is `{"payload":{"amount":"EUR:12.50"}}`. The example combines configured snake-case naming, `@JsonView`, `@JsonIgnore`, generic envelopes, and an endpoint `@CustomSerialization` writer with a currency attribute.

## Configuration

Quarkus maps the following runtime properties through `CatnapConfig`:

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

The default `CATNAP` dialect supports [RSQL-subset array predicates](QUERY_SPECIFICATION.md#catnap-array-predicates), such as `payload(amount)[display_name==ACME]` on the `/catnap/catalog` example. Set `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` to use either field-selection profile without predicates.

For LinkedIn syntax and a different parameter:

```properties
catnap.dialect=LINKEDIN
catnap.parameter=select
```

Then send `?select=payload:(amount)`, appropriately URL-encoded. `@CatnapProjection(enabled = false)` opts out a resource class or method, with method policy taking precedence. Global disablement also bypasses query parsing. A user-provided `ProjectionService` CDI bean overrides the default service. The default service validates configuration during startup; REST providers defer access until runtime because Quarkus constructs providers during static initialization.

## Serialization and failure behavior

The request filter validates and compiles the decoded query before resource invocation. It stores immutable projection state in request properties, which survive asynchronous completion. Missing or ASCII-blank queries preserve the native response path and allocate no projection tree or capture buffer.

For eligible responses, the response filter resolves the native provider using Quarkus REST's ordering. It captures the provider's output in a bounded sink before transport commitment. A native `ServerMessageBodyWriter` retains its `ServerRequestContext`, effective generic type, resource metadata, configured mapper, and endpoint writer/view settings. Standard JAX-RS writers retain their entity type and annotations. Existing application writer interceptors run once with the original entity, before projection.

This distinction matters in the pinned Quarkus version: registering a global JAX-RS writer interceptor changes Jackson to its legacy `writeTo` path, which does not retain every native endpoint customization. Catnap does not install such an interceptor. When an application itself installs one, Catnap preserves the same legacy serialization behavior the application normally has.

The captured JSON is parsed using arbitrary-precision numbers, projected through the Jackson 2 tree adapter, and written through Quarkus's native byte-array response writer. The application mapper is not replaced. Successful `application/json` and `application/*+json` responses are eligible. Errors, 204/205/206, HEAD, non-JSON bodies, and responses already marked with a non-identity content encoding bypass projection.

Status, content type, and unrelated headers are retained. Body-dependent validators and range metadata are removed, and content length is recalculated. Duplicate parameters, invalid syntax, and compilation limits produce HTTP 400 problem JSON. The error payload is already encoded so an endpoint's custom domain serializer cannot accidentally serialize the problem body as its own return type. Serialization, malformed JSON, payload/depth/work bounds, and evaluation failures produce HTTP 500 before commitment without falling back to the original payload.

Native JSON writers must finish serialization before returning. Direct file transfers, transport resets, and custom transport commit listeners are not finite JSON serialization operations and fail closed when a query requires projection.

## Bounded finite JSON publishers

Quarkus's native `Multi` response path bypasses ordinary response filters. Use the explicit `CatnapQuarkusResponses.jsonArray` helper when a publisher represents one finite JSON document:

```java
@Inject CatnapQuarkusResponses responses;
@Inject ObjectMapper mapper;

@GET
@Path("/quotes")
@Produces(MediaType.APPLICATION_JSON)
public Uni<Response> quotes(@Context ServerRequestContext request) {
  var writer = mapper.writerFor(new TypeReference<Envelope<Quote>>() {})
      .withView(Public.class).withAttribute("currency", "EUR");
  return responses.jsonArray(request, quotePublisher(),
      (value, output) -> writer.writeValue(output, value));
}
```

Here `ServerRequestContext` is Quarkus REST's `org.jboss.resteasy.reactive.server.spi.ServerRequestContext`. The source can be a Mutiny `Multi<T>` or another `java.util.concurrent.Flow.Publisher<T>`. Return the resulting `Uni<Response>` to Quarkus; do not subscribe separately. Add application status/headers by mapping the returned response through `Response.fromResponse(response)`.

The serializer writes one UTF-8 JSON value using the application's configured writer and complete generic/view/attribute context. Supply that context explicitly: endpoint `@JsonView`, `@CustomSerialization`, and domain-object writer interceptors do not configure this callback. Ordinary finite responses continue to use the automatic native writer capture described above.

The helper requests one item at a time and accumulates encoded bytes, including brackets and separators, under a **total document** `max-payload-bytes` bound. It retains no list of source objects. Only after source completion does it materialize the complete array and evaluate the query once. Empty publishers produce `[]`; empty selections preserve item count as `[{},{}]`. The result passes through the native byte-array response writer, and the finite filter recognizes that exact entity to avoid a second projection. Active projection removes stale representation headers and sets the selected content length.

Source/serializer failures and byte/depth/work-limit failures cancel upstream and produce HTTP 500 before any original document is committed. Invalid queries remain HTTP 400 before resource invocation. Quarkus's cancellable `Uni` response lifecycle propagates client disconnection into the collector. Keep that native cancellation behavior enabled for these responses. Tests verify disconnect cancellation while the source is waiting, cancellation at the byte bound, and constant-stack one-item demand for a synchronous source.

An absent query or disabled projection skips the extra tree/evaluation. The explicit collection helper still imposes its total byte bound so it cannot accumulate an unbounded document. The source must be finite; use NDJSON/SSE for unbounded sources. Native serialization callbacks execute on the source's thread. Keep blocking I/O out of event-loop callbacks and use an application-owned bounded execution policy for blocking sources. Source-side allocations and queues remain application-owned.

The example endpoints `/catnap/publisher`, `/catnap/publisher-empty`, and `/catnap/publisher-large` exercise the helper. Raw `Multi<T>` returns do not automatically use it.

## NDJSON and SSE responses

The same `CatnapQuarkusResponses` bean provides `encoder`, `ndjson`, `encodedNdjson`, and `sse`. Return a `CatnapStream` **directly** from the resource method; the extension installs a dedicated native REST handler for that return type:

```java
@GET
@Path("/quotes.ndjson")
@Produces("application/x-ndjson")
public CatnapStream quotes(@Context ServerRequestContext request) {
  var writer = mapper.writerFor(new TypeReference<Envelope<Quote>>() {})
      .withView(Public.class).withAttribute("currency", "EUR");
  JsonStreamEncoder<Envelope<Quote>> encoder = responses.encoder(
      request, (value, output) -> writer.writeValue(output, value));
  return responses.ndjson(request, quotePublisher(), encoder)
      .header("X-Application", "preserved");
}
```

The source is a `Flow.Publisher`, including Mutiny `Multi`. The serializer callback supplies the application's native writer and full type/view/attribute context. As with the finite publisher helper, resource annotations do not configure that callback. Queries and encoders belong to one connection; a `CatnapStream` may start once. Do not wrap it in `Response`, `Uni`, or another publisher, or subscribe to its source separately.

Typed NDJSON emits compact JSON plus LF per source item. Object/array/scalar/null shapes and record counts are preserved; empty selections remain `{}`. Use a JSON null node or a non-null object whose serializer emits null, since Flow forbids null items. `encodedNdjson` accepts arbitrary UTF-8 byte chunks, frames complete records incrementally, and always validates declared raw JSON. LF/CRLF and split UTF-8 are supported; blank records, bare CR, invalid JSON/UTF-8, oversize/deep records, and unterminated nonempty tails fail. Input chunks are application-owned and must be bounded; the adapter does not collect a chunk's records into a list.

For SSE, create native `OutboundSseEvent` envelopes with explicit data markers:

```java
Multi<OutboundSseEvent> events = quotes.map(quote -> sse.newEventBuilder()
    .id("42").name("quote").reconnectDelay(1500).comment("heartbeat")
    .data(StreamPayload.json(quote)).build());
return responses.sse(request, events, encoder);
```

Declare `@Produces(MediaType.SERVER_SENT_EVENTS)` on the endpoint. `StreamPayload.json(value)` is typed JSON; `encodedJson(text)` declares complete raw JSON and always validates it; `text(text)` preserves intentional text. A null event data field is a control/heartbeat event, while `StreamPayload.json(null)` emits JSON null data. Use the native builder's comment-only event for heartbeats. The supported envelope contract preserves standard SSE IDs, event names, retry values, multiline comments, and data; Catnap does not implement replay storage or nonstandard SSE extensions.

The native Quarkus SSE publisher handler flushes headers before subscribing. Catnap's handler instead prepares the first complete payload before its first write, enabling HTTP 500 for first-payload failures. It uses native Quarkus transport and SSE formatting, with narrow corrections for the pinned writer: multiline comments are emitted as comment lines immediately before the native event, without an intervening event separator; CRLF/CR text line endings normalize to LF; IDs/names stop at their first line break. These preserve one input event as one wire envelope and prevent metadata line injection. Already-selected data uses the native byte-array writer rather than another JSON serialization.

## Streaming lifecycle, limits, and metadata

Catnap requests one item, prepares it, waits for the native write acknowledgment, and only then requests another. It has no item queue and does not increase demand based on a `RestMulti` setting. Terminal signals wait for an outstanding write; source failure cancels upstream immediately, and disconnects cancel it and release the request context. A source that emits without demand is rejected. Native socket/write buffers add their own bounded storage; real HTTP tests verify that production stops while a client stops reading.

For push producers, choose an explicit bounded overflow policy and arrange cancellation cleanup. Avoid Mutiny's unbounded buffering policy for an uncontrolled push source. Serialization runs on the source's execution thread; keep blocking work off event loops and use an application-owned bounded executor when required. The helper introduces no scheduler or unbounded worker pool. Application objects, upstream queues, and input chunks remain application-owned.

`max-payload-bytes` bounds native serialized input and selected output per payload. Combined SSE envelope metadata has a separate bound of the same size, with bounded native framing overhead. Parsing enforces JSON depth, and active queries enforce evaluation work. Missing/blank queries and endpoint/global disablement skip additional tree construction for typed JSON while explicit helpers retain their byte/framing bounds. Declared raw JSON is always parsed. Each completed item releases its temporary tree.

Set headers or a successful body-bearing status through `CatnapStream.header`/`status` before returning it. Native `@ResponseHeader` and `@ResponseStatus` customizers are also preserved; explicit response settings take precedence. Active projection removes stale length, validators, digests, and range metadata immediately before commitment. Standard response filters are bypassed for these streams, just as on Quarkus's native publisher path.

Invalid queries produce HTTP 400 before source subscription. First-payload errors use a generic HTTP 500; after commitment, default behavior cancels upstream and resets the response so a truncated transfer remains detectable. No failure emits the original unprojected payload. If an application's SSE protocol defines an error event, the optional final `sse` argument maps a committed-stream failure to one native `OutboundSseEvent` containing text data, for example `failure -> sse.newEventBuilder().name("application-error").data("temporary failure").build()`. That event is separately bounded, unprojected, and followed by completion. First-item failures, cancellation, and native transport errors are not converted into events. NDJSON has no invented error-record format.

Production examples are `/catnap/stream/ndjson`, `/catnap/stream/sse`, `/catnap/stream/encoded`, and `/catnap/stream/lifecycle/{protocol}`. Raw `Multi<T>`/`Multi<OutboundSseEvent>` methods do not automatically acquire Catnap projection; use the explicit response helper.

## Verification

```bash
./gradlew :catnap-quarkus:check :catnap-quarkus-deployment:check :examples:quarkus:test
```

The shared finite HTTP contract exercises configured dialects, parameter renaming, cache disablement, native serializer context, async results, precision, errors, metadata, limits, and endpoint/global disablement. Additional checks exercise application message-body writers and name-bound interceptors, direct byte-array output, vendor JSON media types, and runtime configuration defaults. A dependency guard keeps augmentation libraries and other framework adapters out of the runtime artifact.

The finite-publisher tests additionally cover configured dialects, explicit writer context, empty arrays/selections, metadata, source and serialization failures, total document bounds, and real HTTP disconnect cleanup. Collector unit tests cover one-item demand, stack safety, delimiter accounting, and cancellation before/during serialization. Full-repository verification includes `./gradlew build compatibilityTest` and core execution on Java 17, 21, and 25.

The production streaming tests run the shared wire and lifecycle contracts for the configured dialects over the real Quarkus server, covering early delivery, query isolation, slow consumers, disconnect cleanup, pre/post-commit failures, and bounded application error events. Additional tests cover split UTF-8, declared raw JSON, metadata and line endings, disabled-projection bounds, endpoint header/status customizers, one outstanding native write, delayed termination, push-overflow rejection, and constant-stack synchronous acknowledgments.
