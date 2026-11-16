# Version 1 conformance corpus

These resources are normative examples of the Catnap query contract in `docs/QUERY_SPECIFICATION.md`. They are packaged in the `catnap-testkit` JAR under `/com/github/gregwhitaker/catnap/conformance/v1/`, so core, JSON integrations, and framework test suites can consume the same cases. No production parser or evaluator is included here.

The four data files are UTF-8 JSON with `schemaVersion: 1` and a `cases` array. `manifest.json` lists them via `resource` entries. IDs are unique within each resource. JSON object order is not significant; array order, JSON types, exact strings, and numerical precision are significant. Readers must retain arbitrary-precision numbers. Expected results are explicit fixtures, independent of the evaluator under test.

| Resource | Case structure | Consumer |
|---|---|---|
| `projection.json` | `id`, `reason`, `queries`, `input`, `expected` | Core compiler/evaluator and native JSON tree bindings |
| `syntax-errors.json` | `id`, `query`, `input`, `error` | Core compiler; `input` is present but must not be evaluated |
| `http-binding.json` | `id`, `parameters`, `expected` | HTTP parameter adapters; applies to both dialects |
| `streams.json` | `id`, `reason`, `format`, `queries`, `input`, `expected` | Core and JSON binding tests for logical stream payloads |

`queries` is an array of `{ "dialect": "GOOGLE" | "LINKEDIN", "expression": string }`. All variants in a case must produce the same expected value. Every success/stream case includes both dialects; additional variants exercise equivalent spellings and ordering. Do not validate only the first query.

Syntax failures use a single `query` and an `error` object containing `code`, `offset`, `line`, `column`, and `at`. Locations follow the specification's UTF-16 convention; `at` is the offending code point or `<EOF>`, except that quote/escape errors point to the opener specified by the diagnostic contract. Message wording is intentionally absent. A conformance runner must assert the code and all three coordinates against the actual compiler exception.

HTTP `parameters` contains the already decoded values for occurrences of the configured query parameter, in request order. An empty array means absent. `expected.action` is `BYPASS`, `COMPILE`, or `REJECT`. `COMPILE` includes the exact, untrimmed `expression`; a syntax error may occur in the next stage. `REJECT` includes `code: "MULTIPLE_QUERY_PARAMETERS"`. A conformance runner must exercise these cases for both dialects without constructing a JSON tree on bypass.

Stream `format` is `NDJSON` or `SSE`. NDJSON `input` and `expected` contain logical JSON records, not arbitrary byte chunks. SSE entries are logical frames:

- `kind: "json"` has `data` containing any JSON value, including null.
- `kind: "text"` has a string `data` that must pass unchanged, even when it resembles JSON.
- `kind: "control"` has no `data` and must pass unchanged.
- Optional `id`, `event`, `retry` (nonnegative integer milliseconds), and `comments` (array of strings) are envelope metadata, outside projection scope. Missing and empty metadata are distinct. The fixture's `kind` is test configuration, never an SSE wire field.

For each stream query, a conformance runner must compile once and emit the corresponding expected sequence in order, with matching cardinality and preserved metadata. NDJSON output additionally requires compact UTF-8 JSON and a newline per record. Transport-level splitting, framing failures, early delivery, slow-consumer behavior, cancellation, and post-commit errors require the framework wire and lifecycle tests; the logical fixtures alone do not prove those properties.

## Verification

`ConformanceCorpusTest` validates resource structure, unique IDs, paired dialect coverage, position/marker consistency, preserved scalar/container shape, and streaming metadata/cardinality. It also checks precision and diagnostic coverage without calculating expected projections. The core executes all projection, syntax-error, and logical-stream query variants; the native JSON bindings reuse the successful projection and stream cases. Shared HTTP services and framework suites verify request binding and actual transport behavior.

Run from the repository root:

```bash
./gradlew :catnap-testkit:test :catnap-core:test
./gradlew :catnap-jackson2:test :catnap-jackson3:test :catnap-kotlinx-json:test
./gradlew streamingTest
```
