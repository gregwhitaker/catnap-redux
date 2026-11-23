# Spring integration

The current adapters support annotated Spring MVC and WebFlux controllers on Spring Boot 4.1.2 / Spring Framework 7.0.10, with Java 17 bytecode. They use the application's native JSON writer before evaluating the query. Naming, ignored properties, `@JsonView`, declared types, registered serializers, and converter/encoder writer customizations therefore determine the JSON field names available for selection.

Both adapters implement finite responses and explicit NDJSON/SSE helpers. MVC supports bounded emitter sends and publisher subscriptions; WebFlux supports controllers and functional routes. Finite WebFlux functional responses have no automatic query binding; streaming functional routes bind through the explicit helper.

## Choose a starter

For complete product-detail, paginated-search, and image/variant filtering workflows, start with the [MVC catalog walkthrough](examples/spring-boot-mvc/README.md) or [WebFlux catalog walkthrough](examples/spring-boot-webflux/README.md). Both use the same seed data and real HTTP catalog contract, with framework-native controllers and models.

Configure a repository using the [installation guide](INSTALLATION.md), then choose the starter for your application:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter") // MVC
    // For WebFlux, use catnap-spring-boot-starter-webflux instead.
}
```

The [MVC starter](https://github.com/gregwhitaker/catnap-redux/blob/main/catnap-spring-boot-starter/README.md#install) and [WebFlux starter](https://github.com/gregwhitaker/catnap-redux/blob/main/catnap-spring-boot-starter-webflux/README.md#install) READMEs include Maven examples. The starters register the matching adapter automatically in a Boot application.

MVC does not acquire WebFlux or Reactor through Catnap. WebFlux does not acquire MVC or the Servlet API. Auto-configuration references adapter modules optionally and activates the matching web application type. Dependency-boundary checks run as part of `check`.

Controllers return their usual objects, `ResponseEntity`, or supported asynchronous response types. They do not need to invoke the evaluator. For the examples:

```bash
./gradlew :examples:spring-boot-mvc:bootRun
# Alternatively: ./gradlew :examples:spring-boot-webflux:bootRun

curl --get http://localhost:8080/catnap/quote \
  --data-urlencode 'fields=payload(amount)'
```

The result is `{"payload":{"amount":"EUR:12.50"}}`. The currency value comes from the application's custom serializer and writer attribute. The `internal` and `ignored` model properties remain unavailable to the public view even if requested explicitly.

## Configuration

Boot configuration metadata is generated for these properties:

| Property | Default | Meaning |
|---|---|---|
| `catnap.enabled` | `true` | Enable projection and automatic finite adapter registration |
| `catnap.parameter` | `fields` | Decoded query parameter name |
| `catnap.dialect` | `CATNAP` | `CATNAP`, `GOOGLE`, or `LINKEDIN` |
| `catnap.max-query-length` | `16384` | Query UTF-16 units |
| `catnap.max-query-depth` | `64` | Selector nesting |
| `catnap.max-selectors` | `4096` | Parsed selector segments |
| `catnap.max-json-depth` | `128` | JSON traversal depth, root zero |
| `catnap.max-work` | `1000000` | Evaluation work, including selector matching |
| `catnap.cache-capacity` | `256` | Maximum compiled queries; zero disables caching |
| `catnap.max-payload-bytes` | `1048576` | Maximum finite document or individual stream payload, and projected output |

The default `CATNAP` dialect supports [RSQL-subset array predicates](QUERY_SPECIFICATION.md#catnap-array-predicates), such as `images(url)[size==medium]` on `/api/products/trail-pack-28`. Set `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` to use either field-selection profile without predicates. For example:

```properties
catnap.parameter=select
catnap.dialect=LINKEDIN
catnap.max-payload-bytes=2097152
```

Then request `?select=payload:(amount)`, URL-encoded as appropriate. There is no syntax auto-detection. Invalid bounds fail application startup. A custom `ProjectionService` bean can supply application-specific common settings and cache ownership.

`@CatnapProjection(enabled = false)` opts a controller or method out. A method annotation takes precedence over its controller annotation. Global disablement always bypasses projection, including query parsing.

## Response contract

An omitted or ASCII-blank parameter uses the normal response writer without another JSON tree or body buffer. Duplicate parameter values, invalid syntax, and compilation-limit failures return HTTP 400 with `application/problem+json`, a stable `code`, and the title `Invalid partial response query`. Validation runs before invoking an enabled controller, including before asynchronous source creation.

Finite projection applies to successful `application/json` and `application/*+json` bodies. Error statuses, 204/205/206, non-JSON bodies, and responses already carrying a non-identity `Content-Encoding` retain their native response path. NDJSON and SSE are separate formats. Empty projected objects remain `{}` with the original successful status.

After projection, Catnap preserves status, JSON media type/charset, and unrelated headers. It removes `ETag`, digest fields, range metadata, and the original content length, then supplies the projected length. Apply transport compression after projection. Application conditional-request logic remains responsible for validators appropriate to its resource and query parameters.

For a finite document, the adapter captures the native encoded bytes, parses one precise JSON tree, evaluates the query, and serializes the selected tree. This preserves converter-specific behavior without reconstructing an application's `ObjectWriter`. The final JSON is compact; native pretty-print whitespace is not preserved. Serialization can therefore occur twice on the queried path. The [performance guide](PERFORMANCE.md) describes measured costs and operating bounds.

The payload limit bounds Catnap's finite response capture. It does not limit domain objects already allocated by application code or buffers allocated inside a native encoder before Catnap receives them. WebFlux finite JSON arrays follow the framework's finite-document collection semantics; use NDJSON for an unbounded source.

Malformed serialized JSON, payload/depth/evaluation limits, or projection failures never fall back to the unprojected body. Before commitment, they produce a server error through the framework/container error path. The client query remains a 400 concern; failure to serialize or project the server's data is a 500 concern.

## Installation without Boot

MVC applications register a `CatnapMvcInterceptor` through `WebMvcConfigurer.addInterceptors` and a `CatnapMvcFilter` with their servlet container. Construct both from the same `ProjectionService`/`ProjectionSettings`. The filter must support asynchronous processing and both `REQUEST` and `ASYNC` dispatches. It defers a finite JSON flush until projection completes, while other media types flush normally.

WebFlux applications register `CatnapWebFluxFilter` as a `WebFilter` and `CatnapWebFluxHandlerAdapter` as a `HandlerAdapter`, passing the existing `RequestMappingHandlerAdapter` as its delegate. Catnap keeps the query in exchange attributes. It uses no request-thread `ThreadLocal`, does no blocking I/O, and releases pooled input buffers on successful projection, errors, and cancellation.

## MVC NDJSON and SSE

The MVC starter supplies a singleton `CatnapMvcStreams` bean. Create an encoder and emitter per request, then return the emitter's native `response()` to Spring:

```java
@GetMapping("/quotes.ndjson")
ResponseBodyEmitter quotes(HttpServletRequest request, HttpServletResponse response) {
  ObjectWriter writer = mapper.writerFor(new TypeReference<Envelope<Quote>>() {})
      .withView(Public.class)
      .withAttribute("currency", "EUR");
  JsonStreamEncoder<Envelope<Quote>> encoder = streams.encoder(
      request, (value, output) -> writer.writeValue(output, value));
  var emitter = streams.ndjson(request, response, encoder);
  emitter.subscribe(quotePublisher());
  return emitter.response();
}
```

The serializer callback writes one UTF-8 JSON value. Supply the application's configured writer, declared generic type, view, filters, and attributes explicitly; controller annotations do not configure this callback. An encoder captures the request's immutable query and belongs to one connection. Missing queries and disabled projection skip additional tree construction for typed values while retaining byte bounds.

`subscribe` accepts either `java.util.concurrent.Flow.Publisher<T>` or `org.reactivestreams.Publisher<T>` before the emitter starts. It requests one item after each successful native send/flush. Reactor publishers can use the latter overload, but the MVC module has no Reactor or WebFlux dependency. Bare publisher/emitter return values are not automatically projected: use these explicit helpers.

For imperative producers, `send(value)` returns a `CompletionStage<Void>` that acknowledges the native send/flush. The application must observe failures. The bounded pending queue rejects excess sends with `RejectedExecutionException`; already accepted sends continue. `complete()` drains accepted items before completing, and `fail(cause)` terminates after them. `termination()` signals completion or failure and can release producer resources. Use the wrapper for sends and lifecycle operations; `response()` is only the native controller return value.

`sse(request, response, encoder)` accepts Spring `ServerSentEvent<StreamPayload<T>>` values. `StreamPayload.json(value)` represents typed JSON, `encodedJson(text)` represents complete raw JSON that is always validated, and `text(text)` preserves intentional text. Null envelope data is a control/heartbeat event; `StreamPayload.json(null)` emits JSON null data. Native `SseEmitter` framing retains IDs, names, retry values, multiline comments/data, and heartbeat/control frames. Empty JSON projections still emit an event.

`encodedNdjson(request, response, inputStream, encoder)` owns its input stream. It reads records incrementally, supports LF/CRLF and split UTF-8, and rejects blank records, bare CR, malformed JSON/UTF-8, oversized records, and unterminated nonempty tails. It closes the input on EOF, failure, cancellation, timeout, and service shutdown. An input implementation's `close()` must unblock a pending read. Reading and native writes run on the service's bounded executor.

Provide an `MvcStreamOptions` bean to change the default four workers, 64 queued executor tasks, 16 pending sends per connection, and 30,000 ms native async timeout. The timeout covers the async response's total lifetime, not idle time; zero disables it. There is at most one scheduled worker task per connection. Executor saturation terminates affected streams rather than allocating an unbounded queue. Payload input/output and combined SSE metadata are each bounded by `max-payload-bytes`; native framing and container/socket buffers add their own storage. Application objects and upstream queues remain application-owned.

The startup filter delays source subscription and writes until Spring has initialized the native emitter, including `Callable<ResponseBodyEmitter>` dispatches. Disconnect callbacks, timeout, and failures cancel upstream. A disconnect may be observed only when the container reports it or a write fails; applications may supply heartbeat events. Service shutdown cancels sources and closes owned input streams; the servlet container owns transport shutdown.

First-payload failures use the native HTTP 500 path. Failures after commitment terminate the stream without sending the original payload. An optional final `sse` argument maps a committed-stream application failure to one bounded, unprojected `ServerSentEvent<String>` followed by completion. It must contain client-safe data. Cancellation, failed servlet writes, and executor saturation do not attempt an error event. NDJSON has no invented error-record format. Active projection removes stale representation headers before commitment.

Without Boot, register `CatnapMvcStreamFilter` before `CatnapMvcFilter`, with async support and `REQUEST`/`ASYNC` dispatches. Construct and close one `CatnapMvcStreams` service for the application. The stream service and startup filter remain installed when projection is globally disabled so explicit endpoints retain their native output and bounds.

## WebFlux NDJSON and SSE

The reactive starter supplies a `CatnapWebFluxStreams` bean. Without Boot, construct it with the same `ProjectionService` as the query-binding adapter. It provides `encoder`, `writeNdjson`, `writeEncodedNdjson`, and `writeSse` operations. Return the resulting `Mono<Void>` to Spring; do not subscribe to it separately.

Create an encoder per request using the application's native serializer operation. For Jackson, pass a configured `ObjectWriter` with the actual declared generic type, view, filters, and writer attributes:

```java
@GetMapping("/quotes.ndjson")
Mono<Void> quotes(ServerWebExchange exchange) {
  ObjectWriter writer = mapper.writerFor(new TypeReference<Envelope<Quote>>() {})
      .withView(Public.class)
      .withAttribute("currency", "EUR");
  JsonStreamEncoder<Envelope<Quote>> encoder = streams.encoder(
      exchange, (value, output) -> writer.writeValue(output, value));
  return streams.writeNdjson(exchange, quotePublisher(), encoder);
}
```

The serializer writes exactly one UTF-8 JSON value into a bounded stream before projection. This retains naming, visibility, custom serializers, generic context, and exact numbers. Since the helper writes the response directly, supply its complete writer context explicitly; controller `@JsonView` annotations and a separately configured Spring codec's customizations are not automatically applied to this callback. Other serializers may use the same callback API.

`writeNdjson` produces compact JSON followed by LF for every source item. Empty selections remain `{}` records, and root arrays/scalars/nulls preserve their shape. Reactive Streams forbids Java null source items; use a JSON null node or a non-null domain wrapper whose serializer emits JSON null.

`writeEncodedNdjson` accepts a `Publisher<byte[]>` of already-encoded UTF-8 chunks. Records can span chunks, including split UTF-8 codepoints; a chunk can contain multiple records. Framing accepts LF/CRLF and rejects blank records, bare CR, invalid UTF-8, oversized records, and unterminated nonempty tails. It processes records incrementally even within a large chunk, without collecting that chunk's records into a list. Producers remain responsible for bounding their input chunks.

For SSE, pass native Spring envelopes containing explicit payload markers:

```java
Flux<ServerSentEvent<StreamPayload<Envelope<Quote>>>> events =
    quotes.map(quote -> ServerSentEvent.builder(StreamPayload.json(quote))
        .id(quote.payload().id())
        .event("quote")
        .retry(Duration.ofMillis(1500))
        .build());
return streams.writeSse(exchange, events, encoder);
```

`StreamPayload.json(value)` serializes typed JSON. `StreamPayload.encodedJson(text)` declares complete raw JSON and always validates it, including without a query. `StreamPayload.text(text)` preserves intentional text, even if it resembles JSON. Null event data is a heartbeat/control event; a non-null `StreamPayload.json(null)` represents JSON null data. Spring's native SSE writer preserves IDs (including empty IDs), event names, retry values, multiline comments, text data, and control frames. Catnap does not parse or rewrite SSE wire fragments.

The query remains fixed in the exchange/encoder. Encoders are connection-owned and must not be shared across requests. Controller annotations and global disablement retain their usual query-bypass behavior. The helper bean remains available when projection is globally disabled so existing endpoints continue to work; explicit streaming helpers still enforce their byte/framing bounds. Missing/blank queries skip additional tree construction for typed values. Declared raw JSON is always validated.

Functional routes can use the same helpers from a `ServerResponse` body writer:

```java
return ServerResponse.ok().build((exchange, context) -> {
  var encoder = streams.<Quote>encoder(exchange,
      (value, output) -> writer.writeValue(output, value));
  return streams.writeNdjson(exchange, quotes, encoder);
});
```

When no controller has bound the query, `encoder` validates it before subscribing to the stream. Invalid functional-route queries raise an HTTP 400 `ResponseStatusException`; annotated controllers retain the structured problem response described above.

## WebFlux streaming lifecycle and limits

Catnap requests one logical record/event group at a time, advancing when the current group has been consumed by the transport. Its handoff has no source prefetch or unbounded queue. Reactor context, ordering, cancellation, and errors propagate through the returned publisher. Native socket buffers have their own bounds. The adapter uses heap buffers for framed events so cancellation of an unopened event publisher does not leave pooled buffers unreleased; native transport owns its own output buffers.

Use a demand-aware publisher. For push APIs that cannot slow down, select an explicit policy such as `Flux.create(..., FluxSink.OverflowStrategy.ERROR)` or an application-owned bounded queue. Register the source's cleanup through `onDispose` so both disconnect and overflow failure stop the producer. Catnap introduces no silent drop policy. The serializer callback runs on the publisher's execution thread; keep blocking I/O out of Netty event-loop callbacks and use a bounded application scheduler when offloading is needed.

`maxPayloadBytes` bounds native serialized input before parsing and projected output. SSE metadata fields have a separate combined bound of the same size; native framing can add bytes. JSON parsing enforces the configured nesting limit, and active queries enforce evaluation limits. Source objects, preallocated input chunks, and application-owned upstream queues are outside this bound.

Native WebFlux commitment waits for the first complete encoded record/event. First-payload errors can produce HTTP 500; errors after commitment cancel upstream and terminate the stream. Failure messages do not expose parser input, and a failed projection never falls back to the original payload. Cancellation is not recovered.

If an application's SSE protocol defines an error event, the optional final `writeSse` argument can map a committed-stream failure to one native `ServerSentEvent<String>`. It is byte-bounded, is intentionally not projected, and ends the response. For example, `failure -> ServerSentEvent.builder("temporary failure").event("application-error").build()`. The callback must return data intended for clients. First-item failures still use the HTTP error path; the default emits no error event. NDJSON has no invented error-record format.

Active projection removes stale representation validators, digests, ranges, and content length before commitment. Unrelated headers remain. Unprojected streams retain their metadata. Framework framing and flushing stay native.

## Verification

```bash
./gradlew :catnap-spring-webmvc:check :catnap-spring-webflux:check
./gradlew :catnap-spring-boot-autoconfigure:test
./gradlew :examples:spring-boot-mvc:test :examples:spring-boot-webflux:test
```

Both examples add CATNAP predicate tests against serialized names, custom money strings, and view-hidden fields at `/catnap/catalog`. MVC also checks filtered arrays and empty matches over `/catnap/stream/images`.

Both examples run the same `FiniteResponseContract` over real HTTP servers for the configured dialects. Additional tests cover disabled adapters, renamed parameters, failure timing, limits, missing-query bypass, async results, header invalidation, exact numeric precision, and native custom serialization. Buffer tests exercise native JSON charsets, flush behavior, cancellation, and pooled-buffer ownership.

WebFlux additionally runs the shared streaming wire and lifecycle contracts over Reactor Netty: configured dialects, native writer context, empty projections, JSON null/text/control events, split NDJSON, early output, concurrent connection isolation, slow-consumer backpressure, disconnect cleanup, and errors before/after commitment. Unit tests verify a single outstanding group even when the consumer requests unlimited groups, explicit push-overflow rejection, Reactor context, and functional-route binding. Example endpoints are `/catnap/stream/ndjson`, `/catnap/stream/sse`, `/catnap/stream/encoded`, and `/catnap/stream/functional`.

MVC runs the same streaming contracts over Tomcat, including early output, metadata, configured dialects, slow consumers, disconnects, failures, and optional application error events. Additional tests verify native async timeout cancellation, Callable emitter initialization, bounded pending/executor queues, one-item publisher demand, and owned-input cleanup before startup and during blocked reads.
