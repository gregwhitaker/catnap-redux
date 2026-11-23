# Catnap query and projection contract, version 1

This is the normative query and projection contract implemented by Catnap's core, native JSON bindings, and framework adapters. The [catalog walkthroughs](examples/README.md#catalog-workflows) demonstrate field selection and predicates through `/api/products`; the `/catnap/*` endpoints cover serializer and streaming contracts.

## Dialects and compatibility

HTTP integrations and the standalone NDJSON launcher default to `CATNAP`; `GOOGLE` and `LINKEDIN` remain explicit options. The core API requires a dialect argument. All three compile to immutable selection plans that select fields from the JSON produced by the application's configured serializer; they never discover additional Java properties or fetch missing data.

The GOOGLE profile follows the comma lists, slash paths, parenthesized children, and asterisk selections described by the [Google fields reference](https://developers.google.com/workspace/drive/api/guides/fields-parameter). The LINKEDIN profile follows comma lists, `field:(children)`, and `$*:(children)` map selections described by the [LinkedIn projections reference](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/projections). These are Catnap profiles, not complete vendor API compatibility. In particular, Catnap has no schema-dependent default fields or unknown-field rejection.

| Selection | CATNAP / GOOGLE | LINKEDIN |
|---|---|---|
| Two fields | `name,price` | `name,price` |
| Nested fields | `price(amount,currency)` | `price:(amount,currency)` |
| One nested path | `price/amount` | `price:(amount)` |
| Array items | `items(id,name)` | `items:(id,name)` |
| Every map value's ID | `byId/*/id` or `byId(*(id))` | `byId:($*:(id))` |
| Complete root | `*` | `$*` |
| Literal reserved name | `"a/b"` | `"a/b"` |

Quoted names in both profiles, GOOGLE wildcard children, and a terminal LINKEDIN `$*` are explicitly supported Catnap behavior. The GOOGLE and LINKEDIN profiles reject predicates. LinkedIn URL path wrappers such as `:(id,name)`, Rest.li decorations, aliases, exclusions, functions, array indexes/slices, and recursive descent are not supported. A literal name that resembles such syntax can be selected by quoting it.

The CATNAP dialect supports the GOOGLE field-selection syntax plus the [RSQL predicate subset](#catnap-array-predicates) below. Existing GOOGLE/LINKEDIN syntax, diagnostics, and evaluation behavior are unchanged. CATNAP is not full RSQL or a drop-in implementation of Catnap 2 predicates.

## Lexical rules and grammar

The compiler consumes the entire decoded query string, without HTTP percent decoding. HTTP adapters decode a parameter exactly once before compilation. Names are case-sensitive and compared exactly, with no Unicode normalization.

Whitespace is only space (U+0020), tab, LF, and CR. It may occur between tokens, never inside a bare name or the two-character `$*` token. Whitespace inside quoted names is significant. Comments are not allowed.

Bare names consist of one or more Unicode scalar values except ASCII control characters U+0000–U+0020, DEL U+007F, and `, : / ( ) * $ [ ] = ~ " \`. This permits numeric map keys, underscores, dashes, dots, and Unicode names. A dot is a literal part of a name, never a path delimiter. Non-ASCII whitespace is rejected outside quoted names (using Java `Character.isWhitespace` or `Character.isSpaceChar`).

Quoted names use JSON double-quoted string syntax, including JSON escapes and `\uXXXX`. Decoding must reject unpaired UTF-16 surrogates, even in escapes. Empty quoted names are valid JSON field names; empty expressions and empty child lists are not. A quoted `"*"` or `"$*"` is a literal field, not a wildcard. Literal and escaped spellings of the same name select the same field.

The following EBNF omits inter-token whitespace. `EOF` requires complete consumption; `name` is a bare or quoted name defined above. Braces mean repetition and brackets mean an optional grammar element, not literal query characters.

```ebnf
google-query     = google-list, EOF ;
google-list      = google-selection, { ",", google-selection } ;
google-selection = google-path, [ "(", google-list, ")" ] ;
google-path      = google-segment, { "/", google-segment } ;
google-segment   = name | "*" ;

linkedin-query     = linkedin-list, EOF ;
linkedin-list      = linkedin-selection, { ",", linkedin-selection } ;
linkedin-selection = linkedin-segment, [ ":", "(", linkedin-list, ")" ] ;
linkedin-segment   = name | "$*" ;
```

A child list attaches to the final segment of a Google path: `a/b(c,d)` equals `a(b(c,d))`. A path cannot continue after a closed child list: `a(b)/c` is invalid. Neither profile accepts a trailing comma or a top-level parenthesized wrapper.

## Selection plan and merging

Conceptually, every selector contains a literal field name or a wildcard, and either **whole value** or a child selection set. Paths desugar into nested selectors. This describes semantics, not a required public AST class layout.

- A terminal selector includes the complete value, including all descendants.
- Duplicate selectors merge their child sets recursively. Reordering or repeating selectors cannot change the selected data.
- A whole-value selection dominates any child selections for that same value: `a,a(b)` equals `a`.
- At an object, a wildcard matches every immediate field name. It is not recursive descent and does not consume an array index.
- When a literal selector and wildcard both match a field, merge their selections before evaluating that value. Whole-value selection wins here too. For example, `*(id),special(name)` selects `id` from every value and also `name` from `special`; `*,special(name)` copies the entire root.
- Wildcards select existing fields only. There is no schema expansion or creation of a missing member.

## Evaluation over JSON

Without an explicit CATNAP predicate, projection removes object members; it never filters array elements, rewrites scalar values, or reshapes containers. These field-selection rules are identical for all three dialects:

| Input at the current selection | Result |
|---|---|
| Object | Visit its fields in the source adapter's iteration order; emit only matching fields and recursively project their values |
| Array | Apply the same selection to each element, recursively through nested arrays; preserve order and count |
| String, number, boolean, or null | Copy the value unchanged; field selectors have no further work at scalar leaves |
| Whole-value selection | Copy the entire selected subtree |

Thus `a(b)` against `{"a":7}` returns `{"a":7}`. Against `{"a":{"c":7}}` it returns `{"a":{}}`. A root scalar such as `7` remains `7` even for `name`. Heterogeneous arrays retain scalar and null entries unchanged; objects in them are projected. This tolerant behavior is deliberate, with no type-mismatch error in version 1.

Missing fields are omitted without error; a present parent remains even if all its requested children are absent. Empty objects and arrays stay `{}` and `[]`. Selected null members remain present as null. Version 1 has no select-nothing operator; an empty expression is invalid. To retain everything explicitly, use the dialect's terminal wildcard.

Object member order follows the source adapter, not query order. For an unordered source adapter, no byte-order guarantee is made. Conformance comparisons ignore object order but preserve array order, JSON types, exact string content, and numerical value/precision. Numerically equivalent spellings such as `1.0` and `1` may compare equal; rounding through binary floating point must not change arbitrary-precision values. Duplicate object keys must already have been resolved or rejected by the JSON integration; they are outside the tree model.

Evaluation does not mutate the source. Mutable output containers and mutable copied subtrees must not alias the source, even for whole-value selections. Immutable JSON nodes may be shared. Reusing a compiled plan across documents, records, events, or concurrent callers must not retain or mix payload data.

## CATNAP array predicates

In `CATNAP`, append a bracketed predicate after a selector's optional children:

```text
images(url)[size==medium]
images(url)[size=in=(medium,large);width>=640]
groups(images(url)[size==medium])[enabled==true]
```

The first query returns only `url` from images whose serialized `size` is the string `medium`. Matching reads the complete original object before projection, so the predicate field need not be returned. Predicates only filter the direct object elements of the selected array. Matching order is preserved. Scalar, null, and nested-array elements do not match a predicate. An existing array remains present as `[]` when nothing matches; a matching object with no selected fields remains `{}`. A predicate-only branch selecting a non-array value contributes no field. An unconditional selection of that same value still applies normally.

The predicate belongs to the final path segment: `catalog/images(url)[size==medium]` equals `catalog(images(url)[size==medium])`. A predicate follows the child list, not a child field (`images(url[size==medium])` would put the predicate on `url`). Only one predicate suffix per selector is accepted; combine conditions inside it. Root predicates, predicates on primitive array elements, and implicit traversal into nested arrays are outside this subset.

| Operation | Syntax |
|---|---|
| Typed equality / inequality | `size==medium`, `size!=small` |
| Numeric ordering | `width<640`, `width<=640`, `width>640`, `width>=640` |
| Equivalent ordering spellings | `width=lt=640`, `width=le=640`, `width=gt=640`, `width=ge=640` |
| Membership / nonmembership | `size=in=(medium,large)`, `size=out=(small,thumbnail)` |
| AND / OR | `size==medium;format==png`, `size==medium,size==large` |
| Grouping | `(size==medium,size==large);format==webp` |

AND binds more tightly than OR. Parentheses override precedence. Evaluation short-circuits left to right. Commas inside membership lists separate arguments; commas elsewhere inside brackets mean OR; commas outside brackets separate projections. The implementation uses this bounded subset of the [RSQL grammar](https://github.com/jirutka/rsql-parser#grammar-and-semantic), with no runtime parser dependency. Word aliases (`and`/`or`), custom operators, functions, regex, and wildcard string matching are not supported. `*` in a predicate value is a literal asterisk. The legacy single `=` is invalid; use `==`.

Predicate selectors are immediate, case-sensitive JSON field names. Dots and slashes are literal characters, with no path traversal or Unicode normalization. Bare selectors and bare argument values exclude whitespace, ASCII controls, DEL, and `" ' ( ) ; , = ! ~ < > [ ]`. A selector can alternatively use the existing JSON double-quoted name syntax, including an empty name or JSON escapes. This quoted-selector support is a Catnap extension.

Unquoted `true`, `false`, and `null` are typed JSON literals. A token matching JSON number syntax is an exact decimal number; an exponent outside the supported `BigDecimal` range produces `INVALID_VALUE`. Other bare tokens are strings. Single- or double-quoted **argument values always mean strings**, so `1`, `"1"`, and `true` are distinct. Argument quoting follows RSQL escaping: a backslash quotes the next character, and a literal backslash is doubled. Unlike JSON-quoted field names, `\n` inside an argument means the letter `n`, not a newline. ASCII controls and unpaired UTF-16 surrogates are rejected in quoted arguments. Quote arguments containing whitespace, reserved characters, or brackets.

Equality is type-sensitive, with exact numeric comparison (`1`, `1.0`, and `1e0` compare equal). There is no string-to-number or string-to-boolean coercion. Ordering requires a numeric argument at compile time and a numeric field at evaluation time. Membership applies typed equality to each listed argument. Missing fields and container-valued fields never match **any** comparison, including `!=` and `=out=`. A present JSON null matches `null`; inequality/nonmembership negate equality only for present scalar fields.

Repeated/wildcard selectors contribute the union of their matching projections **per element**, without duplicates. For example, `items(url)[size==medium],items(id)[size==large]` returns `url` for medium items and `id` for large items; it does not add both fields to both items. If an item matches both branches, their fields merge. `items(id),items(url)[size==medium]` retains every item and adds `url` only to matching objects. An unconditional whole-value selection (`items`) dominates all its conditional alternatives; a filtered whole-value selection (`items[size==medium]`) dominates only for matching elements. Repeating or reordering selectors does not change selected data, though conditional AST alternatives retain source order.

```ebnf
catnap-query     = catnap-list, EOF ;
catnap-selection = google-path, [ "(", catnap-list, ")" ], [ "[", predicate, "]" ] ;
catnap-list      = catnap-selection, { ",", catnap-selection } ;
predicate        = conjunction, { ",", conjunction } ;
conjunction      = constraint, { ";", constraint } ;
constraint       = "(", predicate, ")" | comparison ;
comparison       = predicate-name, scalar-op, argument
                 | predicate-name, set-op, "(", argument, { ",", argument }, ")" ;
scalar-op        = "==" | "!=" | "<" | "<=" | ">" | ">=" | "=lt=" | "=le=" | "=gt=" | "=ge=" ;
set-op           = "=in=" | "=out=" ;
```

All predicate syntax is validated even if a whole-value selector dominates it. Compilation limits count selector segments, predicate groups/comparisons, and arguments. Predicate groups and comparisons add to the enclosing selector depth; arguments use their comparison's depth. Evaluation charges for predicate nodes, scanned members, inspected scalar values, argument comparisons, and rejected array elements, in addition to ordinary projection work. These bounds and source positions use the same options and error types as other dialects.

## Empty requests and HTTP binding

Core compilation requires a non-null, nonblank expression. Null is an API argument error, outside the syntax-error contract. An empty string or a string containing only the four ASCII whitespace characters produces `EMPTY_QUERY`.

HTTP adapters treat an absent `fields` parameter, an empty value, or a value consisting only of those four whitespace characters as bypass: use the native response/stream path and do not create a projection tree. The HTTP layer, not the parser, owns this distinction. Repeated occurrences of the configured parameter are rejected with HTTP 400 (`MULTIPLE_QUERY_PARAMETERS`), even if their values agree or are blank. Nonblank syntax errors return HTTP 400 before response commitment or stream subscription. The HTTP error body's shape is adapter configuration, while the error code and source location remain available.

`fields` is the configurable default parameter name. There is no dialect auto-detection. HTTP decoding must preserve the decoded query for diagnostics; source locations refer to that decoded string, not URL bytes. A successful `{}` response remains a successful response, not HTTP 204.

## Syntax errors and source positions

Diagnostics contain `code`, zero-based `offset`, and one-based `line` and `column`. Offset and column count Java UTF-16 code units, including both units of a supplementary character. CRLF counts as one line break; lone CR or LF also starts a new line. EOF has offset equal to `String.length()`, with the position immediately following the final character. Do not trim before measuring.

Return the first error encountered while reading left to right under the selected grammar. A syntax error cannot produce a usable partial query. Error message wording is not part of conformance; codes and positions are.

| Code | Location and rule |
|---|---|
| `EMPTY_QUERY` | EOF after an empty/ASCII-whitespace-only query |
| `EXPECTED_SELECTOR` | Current token (or EOF) when a field/wildcard is required, including empty groups, doubled/trailing commas, or a missing path segment |
| `EXPECTED_OPEN_PAREN` | Current token (or EOF) after a LinkedIn colon when `(` is required |
| `EXPECTED_CLOSE_PAREN` | EOF when a complete child selection list has no closing `)` |
| `UNEXPECTED_TOKEN` | Current token when a separator, closing delimiter, or EOF is required; includes dialect-specific syntax in the wrong profile |
| `UNTERMINATED_NAME` | Opening quote of a quoted name with no closing quote |
| `INVALID_ESCAPE` | Backslash starting an unsupported/truncated escape or invalid hex sequence |
| `INVALID_NAME` | Unescaped control character in a quoted name, or the start of an unpaired surrogate (backslash for an escaped surrogate) |

CATNAP predicates add these diagnostics:

| Code | Location and rule |
|---|---|
| `EXPECTED_PREDICATE` | Current token/EOF when a comparison or group is required |
| `EXPECTED_OPERATOR` | Current token/EOF after a predicate field when a supported operator is required |
| `EXPECTED_VALUE` | Current token/EOF when a scalar argument is required, including empty membership lists |
| `EXPECTED_CLOSE_BRACKET` | Current token/EOF after a complete predicate when `]` is required |
| `UNTERMINATED_VALUE` | Opening quote of an unterminated predicate argument |
| `INVALID_VALUE` | Nonnumeric ordering argument, out-of-range numeric literal, or ASCII control in a quoted argument |

Within CATNAP predicates, `EXPECTED_OPEN_PAREN` also covers a missing membership-list opener, and `EXPECTED_CLOSE_PAREN` covers a missing group/list closer at the current token or EOF. `INVALID_ESCAPE` covers a trailing backslash in a quoted argument. `INVALID_NAME` also covers unpaired surrogates in predicate names/values. Existing GOOGLE/LINKEDIN error rules remain unchanged.

Lexical errors take precedence once scanning a quoted name starts; they do not override an earlier grammar error. For an invalid bare-name starter or forbidden character outside a quoted name, report `EXPECTED_SELECTOR` when expecting a selector, otherwise `UNEXPECTED_TOKEN`. A complete but unclosed child list reports `EXPECTED_CLOSE_PAREN`; `a(b,` reports `EXPECTED_SELECTOR` because the comma requires another selector first. A missing opening parenthesis after a LinkedIn colon always reports `EXPECTED_OPEN_PAREN`.

Resource limits are separate errors, not syntax errors. The [core API guide](CORE_API.md) defines configurable compilation/evaluation limits and their public error representation. Initial defaults are guardrails, not benchmark-derived latency guarantees. This corpus is run below those limits and does not prescribe limit defaults.

## NDJSON and SSE scope

Compile once before opening a projected connection. Apply that immutable selection independently at the root of every NDJSON record or explicitly JSON-designated SSE payload. A stream is not a JSON array or an object containing `data`; `data` is a selectable name only when the payload itself contains such a field. Records may themselves contain arrays.

Every successfully projected input record/event yields one output record/event, including empty objects, arrays, nulls, and primitive values. CATNAP predicates may reduce arrays inside a payload but never remove the enclosing record/event. Never drop an item because no object fields matched. Order is unchanged. Failure terminates processing under the policy below; one-to-one preservation applies to successful items, not an obligation to emit a failed item.

NDJSON writers emit one compact JSON value plus LF, encoded as UTF-8, under `application/x-ndjson`. Where an integration accepts encoded NDJSON, it must frame complete records across arbitrary byte chunks before parsing: accept LF and CRLF; reject empty or whitespace-only records, bare CR delimiters, malformed UTF-8/JSON, and a nonempty final record without a newline. An empty stream is valid. These strict input defaults deliberately do not enable the optional blank-line skipping allowed by the [NDJSON specification](https://github.com/ndjson/ndjson-spec). Escaped `\n` inside a JSON string is data, not a delimiter. Typed-item emission does not require a raw NDJSON ingestion API.

For SSE, project only the logical JSON `data` value, preserving all supplied event metadata: ID (including an empty ID), name, retry, comments, and control/heartbeat frames. Explicitly textual data passes through, even if it happens to look like JSON. A JSON-designated null payload is data, distinct from a frame with no data. Malformed JSON in a JSON-designated encoded payload fails; do not infer JSON by inspecting text. Framing and multi-line `data:` assembly belong to the framework, as defined by the [SSE specification](https://html.spec.whatwg.org/multipage/server-sent-events.html).

Before headers, query errors fail the request. After commitment, parsing/serialization/evaluation/limit errors cancel upstream and terminate the stream by default. Never emit the unprojected input as a fallback. SSE application-specific error events require explicit configuration; NDJSON has no implicit error-record format. Framework wire and lifecycle tests verify bounded demand, early delivery, cancellation, and disconnect cleanup at the documented integration boundaries.

## Conformance resources

The [version 1 corpus](https://github.com/gregwhitaker/catnap-redux/blob/main/catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) supplies independent expected results for all three profiles, exact syntax diagnostics, HTTP bypass cases, and logical NDJSON/SSE cases. Testkit checks corpus integrity; the core executes every projection, syntax-error, and logical-stream query variant. Native JSON bindings reuse the successful projection and logical-stream cases. Shared HTTP services and framework suites verify request binding and real transport behavior, including the [testkit's wire and lifecycle contracts](https://github.com/gregwhitaker/catnap-redux/blob/main/catnap-testkit/README.md).
