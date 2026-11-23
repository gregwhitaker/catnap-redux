# Native JSON integrations

The JSON modules connect the core engine to native materialized trees. They do not install HTTP filters or own request lifecycle state. Each depends on `catnap-core` and its own JSON library; the core itself remains dependency-free.

Use the [installation guide](INSTALLATION.md) to configure the repository and add the binding with the Catnap BOM.

| Module | Native node type | Tested dependency |
|---|---|---|
| `catnap-jackson2` | `com.fasterxml.jackson.databind.JsonNode` | Jackson 2.21.8 |
| `catnap-jackson3` | `tools.jackson.databind.JsonNode` | Jackson 3.1.7 |
| `catnap-kotlinx-json` | `kotlinx.serialization.json.JsonElement` | kotlinx serialization 1.11.0 |
| `catnap-streams` | Bounded NDJSON text records | Java 17 only |

All bindings support `CATNAP`, `GOOGLE`, and `LINKEDIN`. Core compilation requires an explicit dialect; `CATNAP` supports the [RSQL predicate subset](QUERY_SPECIFICATION.md#catnap-array-predicates). Predicates inspect the serialized JSON before projection, preserving scalar types and exact numeric comparisons.

All modules target Java 17. Framework BOMs may select a compatible newer JSON version; Catnap does not force framework BOM versions into consumers.

## Jackson 2 and 3

Use the application's configured mapper and its selected writer. Writer type, views, attributes, filters, naming rules, and custom serializers determine the JSON that is projected.

```java
var json = new Jackson3Json(applicationMapper);
var query = Catnap.compile("payload(display_name,amount)", QueryDialect.CATNAP);
var writer = applicationMapper.writerFor(new TypeReference<Envelope<Quote>>() {})
    .withView(PublicView.class)
    .withAttribute("currency", "EUR");

JsonNode result = json.project(value, writer, query);
String response = json.write(result);
```

Jackson 2 uses `Jackson2Json` and its own Jackson imports with the same API shape. Its I/O operations declare `IOException`; Jackson 3 uses its native unchecked Jackson exceptions. `project` also accepts `EvaluationOptions` when overriding per-document traversal bounds.

`toTree(value, writer)` writes native tokens, then reads those tokens into a native tree. No JSON text round trip is required. The supplied writer preserves declared generic type and active serialization context. The binding creates an immutable reader configuration with arbitrary-precision number handling and trailing-token rejection without changing the application's mapper. Keep a Jackson 2 mapper's configuration stable after constructing the binding, as with normal concurrent Jackson use.

For already materialized trees:

```java
JsonNode document = json.parse(jsonText);
JsonNode result = query.evaluate(document, json.adapter());
```

`parse` expects exactly one JSON value and retains large integers and precise decimals. Selected containers are rebuilt; mutable binary-node byte arrays are cloned. Missing nodes and deferred `POJONode` values are rejected during evaluation because they are not materialized JSON. Serializers that embed arbitrary raw objects must first produce materialized JSON, using an explicit serialize/parse boundary if necessary. Custom scalar/tree subclasses must honor Jackson's copying contracts.

## Kotlin serialization

Supply the configured `Json` instance and the complete serializer, including generic type arguments and contextual serializer modules:

```kotlin
val binding = KotlinxJson(applicationJson)
val query = Catnap.compile("payload(display_name,amount)", QueryDialect.CATNAP)
val result = binding.project(value, Envelope.serializer(Quote.serializer()), query)
val response = binding.write(result)
```

`toTree` uses `encodeToJsonElement`; there is no JSON text conversion before evaluation. Native `JsonObject` and `JsonArray` results own their collections. Scalar nodes can be shared because they are immutable. JSON null and strings remain distinct from unquoted numbers and booleans. Views are application serializer choices rather than a Kotlin serialization feature; pass the serializer selected for that response.

Use `binding.write` or `PreciseJsonElementSerializer` to encode projected trees. The standard serializer in the pinned Kotlin library converts some large numeric literals through `Double`; Catnap's serializer retains their exact spelling and precision, while delegating container/string encoding to the configured `Json` encoder. This follows the library's [raw numeric literal facility](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-unquoted-literal.html), and validates the numeric grammar before writing an unquoted literal. Raw object/array literals embedded in a primitive and nonfinite numbers must be parsed or normalized into actual JSON values before projection.

## Standalone tree and NDJSON examples

```bash
./gradlew :examples:standalone:run
./gradlew :examples:standalone:installDist
examples/standalone/build/install/standalone/bin/standalone ndjson 'id' < input.ndjson
```

The first command queries one materialized tree twice. The distribution command defaults to `CATNAP`; append `GOOGLE` or `LINKEDIN` to select another dialect. For records containing an `images` array, use `ndjson 'images(url)[size==medium]'` to select only matching image URLs.

The launcher transforms stdin one record at a time, compiling the query once and writing compact UTF-8 JSON plus LF. It does not collect the input stream. Each record is capped at 1 MiB; successful empty projections still emit `{}`, and empty filtered arrays remain `[]` within their records. The example stops on malformed JSON or framing and never writes an unprojected fallback. Tests verify the default and explicit dialects and that the first output is flushed while the source remains open.

`catnap-streams` provides `NdjsonReader` and `NdjsonWriter` independently of any JSON library. The reader accepts LF/CRLF across arbitrary byte boundaries, validates UTF-8, and rejects blank/unterminated records. The writer accepts one already serialized compact JSON value, validates framing and encoded size, and flushes after its LF. JSON syntax validation remains the caller's parser/serializer responsibility. Neither helper continues after an I/O or record-validation failure. Closing a helper closes its underlying stream; the standalone transform leaves caller-owned streams open.

Tree construction and serialization are distinct from the core evaluator's bounds. Apply JSON-parser/serializer constraints and raw-byte limits at application boundaries; evaluation limits cannot retroactively prevent allocation of an input tree. The synchronous NDJSON helpers bound buffering by record size plus a fixed input buffer. They are blocking APIs and must not run directly on framework event-loop threads.

## Verification

Each JSON integration runs the shared projection and logical-stream conformance cases for every applicable dialect. Additional tests cover custom serializers, declared generic types, naming, ignored fields, Jackson views/attributes, Kotlin data classes/contextual serializers, precision, nulls, and mutable ownership. NDJSON tests exercise every chunk split of a multi-byte UTF-8 sample, CRLF, escaped newlines, malformed framing/encoding, size boundaries, and early output.

```bash
./gradlew :catnap-jackson2:test :catnap-jackson3:test :catnap-kotlinx-json:test
./gradlew :catnap-streams:test :examples:standalone:test
```
