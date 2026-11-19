# Changelog

Notable changes to Catnap are recorded here, newest first. This history starts with
the Catnap 3 rewrite.

## Unreleased

Development version: `3.0.0-SNAPSHOT`.

### Added

- Dependency-free query core with Google and LinkedIn field-selection dialects,
  immutable compiled queries, configurable compilation and evaluation limits, and
  an optional bounded query cache.
- `CATNAP` dialect with typed RSQL-subset array predicates: equality, inequality,
  numeric ordering, membership, AND/OR, and grouping. Google and LinkedIn behavior
  remains unchanged. Predicate compilation/evaluation is bounded.
- Typed scalar access for predicates across all native JSON bindings, shared
  conformance cases, and configured-serializer HTTP tests across five frameworks.
- Native JSON bindings for Jackson 2, Jackson 3, and Kotlin serialization.
- HTTP integrations for Spring MVC, Spring WebFlux, Micronaut, Quarkus, and Ktor,
  including separate Spring Boot starters for MVC and WebFlux.
- Bounded NDJSON and JSON-bearing SSE helpers across all five frameworks, with
  per-payload projection, SSE metadata preservation, and cancellation handling.
- Standalone bounded NDJSON readers and writers in `catnap-streams`.
- `catnap-bom` for Catnap dependency alignment and `catnap-testkit` for shared query
  conformance and HTTP/streaming contracts.
- Runnable examples, Java 17/21/25 core compatibility checks, independent Gradle
  metadata and Maven POM consumer verification, JMH benchmarks, and HTTP streaming
  measurement tools.
- Catalog detail, paginated search, and image/variant filtering examples across
  all five web frameworks, with shared realistic seed data, real HTTP contracts,
  and a walkthrough for each application.
- Documentation website with local search, query examples, themes, and framework
  guides, plus GitHub Actions workflows for documentation deployment, verification,
  and GitHub Packages publication and consumption checks.

### Changed

- Made `CATNAP` the default dialect for HTTP integrations and the standalone NDJSON
  launcher. `GOOGLE` and `LINKEDIN` remain explicit options; core compilation still
  requires a dialect argument.
- Replaced the Catnap 2 API with a modular API that projects the application's
  configured serialized JSON. Legacy jars, configuration classes, and annotations
  are not binary-compatible with Catnap 3.
- Set Java 17 as the core and general-purpose library baseline. The Micronaut
  integration requires Java 25; the Gradle build daemon also uses Java 25.
- Updated Spring integrations to Spring Boot 4 and Spring Framework 7 with Jackson
  3. Legacy Spring versions and generic JAX-RS adapters have no compatibility layer.
- Defined explicit query and HTTP contracts: missing fields are omitted, arrays
  retain their order and cardinality unless explicitly filtered by CATNAP, absent
  or ASCII-blank query parameters bypass projection, and repeated parameters or
  invalid queries return HTTP 400.
- Legacy single-`=` predicates migrate to `==` in the CATNAP dialect. JSONP
  callback wrapping remains unsupported. Streaming uses explicit NDJSON/SSE helpers.

### Removed

- Historical `/probe/*` serialization endpoints, their test suites, and the
  `examples:jackson3-probe` module. Internal-view visibility and Ktor generic-type
  checks now run against the current `/catnap/*` integrations.
- Public `HttpProbe` and `ProbeRequest` helper classes from `catnap-testkit`.
  Consumers of these snapshot APIs must migrate to the finite and streaming
  response contracts.

### Fixed

- Micronaut streaming direct-memory failures from synchronous sources through a
  bounded asynchronous handoff.
- Gradle startup from Java 17 launchers by selecting a Java 25 build daemon,
  independently of library runtime requirements.
- Documentation navigation at desktop breakpoints.
- Publication and documentation source URLs to target `gregwhitaker/catnap-redux`.

See the [migration guide](docs/MIGRATION.md) for upgrade steps, the
[compatibility matrix](docs/COMPATIBILITY.md) for tested combinations, and the
[release audit](docs/RELEASE_AUDIT.md) for verification scope and known limitations.
