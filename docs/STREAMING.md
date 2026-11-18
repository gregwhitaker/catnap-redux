# Streaming

Catnap projects finite JSON, newline-delimited JSON (NDJSON), and JSON-bearing server-sent events (SSE). A streaming query is compiled once per connection and applied independently to each logical payload.

Streaming helpers use their integration's configured dialect, which defaults to `CATNAP`. Set `catnap.dialect=GOOGLE` or `catnap.dialect=LINKEDIN` to use either field-selection profile. In Ktor's installation DSL, set `dialect` to the corresponding `QueryDialect` value. There is no syntax auto-detection.

## One query, one payload at a time

For this query:

```text
payload(amount)
```

An NDJSON input may contain:

```json
{"payload":{"amount":"EUR:12.50","display_name":"First"}}
{"payload":{"amount":"EUR:18.00","display_name":"Second"}}
```

The projected records are:

```json
{"payload":{"amount":"EUR:12.50"}}
{"payload":{"amount":"EUR:18.00"}}
```

Every successful input record produces one output record. Order and count remain unchanged, including records that project to `{}`. A stream is not implicitly wrapped in a JSON array. Evaluation limits start fresh for each payload.

## Filter arrays within a payload

With the default `CATNAP` dialect, `images(url)[size==medium]` retains matching image objects inside each record. For example:

```json
{"images":[{"url":"m.jpg","size":"medium"},{"url":"s.jpg","size":"small"}]}
{"images":[{"url":"thumb.jpg","size":"thumbnail"}]}
```

The projected records are:

```json
{"images":[{"url":"m.jpg"}]}
{"images":[]}
```

The second record remains even though no image matches. Predicates inspect the original serialized fields before projection, so `size` need not appear in the output. The same rule applies to arrays inside JSON-designated SSE data: events and their metadata remain present. Google and LinkedIn do not support predicates. See the [RSQL subset](QUERY_SPECIFICATION.md#catnap-array-predicates) for operators, types, and limits.

## NDJSON framing

Writers emit one compact JSON value followed by LF, encoded as UTF-8 under `application/x-ndjson`. Encoded input helpers accept LF and CRLF across arbitrary byte boundaries.

Strict input handling rejects blank records, bare CR delimiters, malformed UTF-8 or JSON, and a nonempty final record without a newline. An empty stream is valid. An escaped newline inside a JSON string is data, not a record boundary.

For framework-independent processing, `catnap-streams` provides bounded `NdjsonReader` and `NdjsonWriter` utilities. They are blocking APIs; do not run them directly on an event-loop thread. See the [standalone JSON and NDJSON examples](JSON_INTEGRATIONS.md#standalone-tree-and-ndjson-examples).

## Server-sent events

Catnap projects only explicitly JSON-designated data. The framework owns the SSE wire format. Event IDs, names, retry values, comments, and control frames are preserved.

```text
id: quote-42
event: quote
data: {"payload":{"amount":"EUR:12.50"}}

```

Textual data passes through unchanged even when it looks like JSON. A JSON null payload is data and remains distinct from an event with no data. Replay storage and reconnection policy belong to the application.

## Choose an explicit streaming helper

Installing a finite-response adapter does not automatically project every emitter, publisher, writer, or byte stream. Use the helper for the transport boundary you own:

| Framework | Supported boundary |
|---|---|
| [Spring MVC](SPRING_INTEGRATION.md#mvc-ndjson-and-sse) | Bounded emitters, imperative sends, Flow/Reactive Streams publishers, and owned NDJSON input streams |
| [Spring WebFlux](SPRING_INTEGRATION.md#webflux-ndjson-and-sse) | Typed/encoded NDJSON and native SSE for controllers and explicit functional routes |
| [Micronaut](MICRONAUT_INTEGRATION.md#ndjson-and-sse-responses) | Typed/encoded NDJSON and native SSE response helpers |
| [Quarkus](QUARKUS_INTEGRATION.md#ndjson-and-sse-responses) | Explicit typed/encoded NDJSON and native SSE response helpers |
| [Ktor](KTOR_INTEGRATION.md#ndjson-and-sse) | Typed/encoded NDJSON, Flow SSE, and imperative sends |

## Bounds and failure behavior

Compilation occurs before opening a projected connection. Query errors fail the request before headers. After commitment, parsing, serialization, evaluation, or limit failures cancel upstream and terminate the stream by default. Catnap never falls back to sending the unprojected input.

Buffering is bounded per payload or record; each integration documents its demand and ownership contract. Cancellation and client disconnects must release upstream resources. A per-record byte limit does not bound domain objects already allocated by application code or buffers created inside a native serializer.

Apply parser, serializer, and transport limits as well as evaluation limits. Consult the [performance guide](PERFORMANCE.md) for measured costs and the [query contract](QUERY_SPECIFICATION.md#ndjson-and-sse-scope) for normative framing and failure rules.
