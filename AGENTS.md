# Working in Catnap

This guide applies to the entire repository. Catnap is a Java/Kotlin library for
JSON field selection, with standalone JSON bindings, HTTP framework integrations,
and bounded NDJSON/SSE helpers. Start with `README.md` and the relevant guide in
`website/content/` before changing behavior.

## Repository map

| Location | Responsibility |
| --- | --- |
| `catnap-core/` | Parser, immutable compiled queries, evaluator, tree interface, and bounded query cache |
| `catnap-jackson2/`, `catnap-jackson3/`, `catnap-kotlinx-json/` | Native JSON bindings and parsing/writing helpers |
| `catnap-http/`, `catnap-streams/` | Shared HTTP policy, bounded encoding, and standalone NDJSON framing |
| `catnap-spring-*/`, `catnap-micronaut/`, `catnap-ktor/` | Framework adapters, configuration, and Spring starters |
| `catnap-quarkus/runtime/`, `catnap-quarkus/deployment/` | Quarkus runtime and augmentation code; Gradle paths are `:catnap-quarkus` and `:catnap-quarkus-deployment` |
| `catnap-testkit/`, `examples/` | Shared conformance/HTTP contracts and runnable applications with real HTTP tests |
| `build-logic/`, `gradle/`, `catnap-bom/`, `verification/` | Convention plugins, dependency catalog, publication metadata, and independent consumer fixtures |
| `benchmarks/` | JMH workloads, HTTP measurement servers, and streaming runner |
| `website/` | Self-contained documentation site: Markdown guides in `content/`, assets, configuration, and build scripts |
| `brand/` | Original brand assets and usage guidance |

## Build and verification

Run Gradle commands from the repository root using `./gradlew` (`gradlew.bat` on
Windows). Java 17+ can launch the wrapper; `gradle/gradle-daemon-jvm.properties`
selects Java 25 for the build daemon because the Micronaut plugin requires it.
Most library bytecode targets Java 17; Micronaut targets Java 25. Core compatibility
tests require Java 17, 21, and 25. Gradle can provision missing toolchains. Keep the
daemon requirement separate from library runtime baselines.

Start with checks for the affected module, for example:

```bash
./gradlew :catnap-core:check
./gradlew :catnap-core:test --tests '*EngineTest'
./gradlew :catnap-spring-webflux:check :examples:spring-boot-webflux:test
```

Choose additional verification according to the change:

| Change | Verification |
| --- | --- |
| JVM code, before handing off | `./gradlew build compatibilityTest` |
| Shared query semantics or conformance fixtures | `./gradlew :catnap-testkit:test :catnap-core:test :catnap-jackson2:test :catnap-jackson3:test :catnap-kotlinx-json:test` |
| Framework HTTP or streaming behavior | `./gradlew streamingTest`, plus focused adapter demand/queue tests |
| Publication, BOM, or dependency boundaries | `./gradlew publicationSmokeTest` |
| Build conventions | `./gradlew -p build-logic build` and `./gradlew spotlessCheck` |
| HTTP measurement tooling | `./gradlew :benchmarks:streaming-runner:smoke` |

The main CI verification combines `build compatibilityTest publicationSmokeTest
:benchmarks:streaming-runner:smoke`. Publication smoke tests publish to the local
`build/repository` and exercise independent Gradle metadata and Maven POM consumers;
they do not require remote package credentials. Timed benchmarks and native-image
builds are separate workflows; consult `benchmarks/README.md` and
`website/content/NATIVE_IMAGES.md` when those are relevant.

For documentation-only changes, run the relevant documentation checks instead of
the JVM suite. Report commands actually run and any failures or checks not run.
Keep generated reports, logs, measurements, and source snapshots in ignored build
directories or CI artifacts; do not commit verification archives to the repository.

## Architecture and behavior contracts

- Keep `catnap-core` free of third-party runtime dependencies and serializer,
  framework, reactive, or Kotlin types. JSON bindings depend on the core; framework
  integrations use the bindings and shared HTTP services.
- Preserve Java baselines and dependency isolation: the MVC starter must not acquire
  Reactor through Catnap, Quarkus augmentation stays out of runtime dependencies,
  and the Catnap BOM must not import framework BOMs.
- Treat `website/content/QUERY_SPECIFICATION.md` and the versioned conformance corpus as the
  query contract. Google and LinkedIn retain shared field-selection semantics;
  CATNAP adds explicit array predicates and is the HTTP default; Google and LinkedIn
  remain configurable. Preserve diagnostic codes and UTF-16 source positions when
  changing parsing.
- Keep compiled queries immutable and reusable across threads. Evaluation must
  preserve source trees, isolate mutable result containers/scalars, retain numeric
  precision and array order, and omit missing fields. Preserve array cardinality
  unless an explicit CATNAP predicate filters elements; preserve logical stream
  record/event cardinality even when a payload's arrays are filtered.
- Project the JSON produced by the application's configured serializer. Preserve
  naming, visibility, custom serializers, generic type, and view context; avoid
  introducing an unconfigured mapper or reflective POJO field-selection path.
- Preserve HTTP binding rules: absent or ASCII-blank parameters bypass projection;
  repeated parameters and invalid queries return HTTP 400. Pass all decoded values
  without trimming or concatenating them. Reuse `ProjectionService` and
  `ResponsePolicy` for shared behavior, and carry request state explicitly rather
  than through thread-locals.
- Keep input, output, nesting, evaluation, and queue limits effective. Finite
  publisher responses need a total-document bound. Streams compile once per
  connection and process one bounded logical record or JSON payload at a time.
  Preserve SSE metadata/text/control events, propagate cancellation, release owned
  resources, and terminate failed streams without an unprojected fallback.

Read `website/content/EXTENDING.md` before adding a binding or framework adapter. Register new
modules in `settings.gradle.kts` and the explicit root task aggregates; published
modules also need `gradle/published-modules.txt`, consumer verification, and updated
compatibility documentation.

## Tests and source conventions

- Use the existing JUnit Platform suites and module-local `src/test/java` or
  `src/test/kotlin` layout. Add regression coverage at the layer owning the behavior.
- Shared query cases belong in
  `catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/`.
  Follow its README, cover all applicable dialects, and write expected results
  independently of the evaluator under test.
- Framework changes should exercise the example application's real HTTP contracts.
  Logical stream fixtures alone do not verify framing, backpressure, disconnects,
  or pre/post-commit errors. Add focused demand/queue tests for handoff changes.
- Follow `.editorconfig`: UTF-8, LF, final newline, two-space Java/YAML indentation,
  and four spaces by default. Spotless uses Google Java Format and ktfmt. Run
  `./gradlew :<module>:spotlessApply` for a module or `./gradlew spotlessApply` for
  broader formatting; inspect the diff for unrelated changes.
- Put shared build behavior in the convention plugins under `build-logic/`.
  Dependency versions belong in `gradle/libs.versions.toml`. After intentional
  dependency changes, run `./gradlew lockDependencies --write-locks`; build-tooling
  changes also need `./gradlew -p build-logic lockDependencies --write-locks`.
  Review lockfile changes. See `build-logic/README.md` for the included-build workflow.

## Documentation and website

The website is self-contained. Edit guides in `website/content/`, including the
catalog walkthroughs in `website/content/examples/`. Page registration, navigation,
and the displayed version live in `website/src/config.mjs`; client behavior lives
in `website/src/`, assets in `website/assets/`, and generation/checking in
`website/scripts/`. Keep links between site pages relative. Use explicit repository
URLs for source code and CI runs; the build must not read outside
`website/`. Preserve root hosting and project paths such as `/catnap/`. Repository
and example READMEs are separate entry points, not website build inputs.

Use Node 22+ (CI uses Node 24), then run from `website/`:

```bash
npm ci
npm test
npm run build
npm run check
```

Use `npm run dev` for local preview. After client/layout changes, inspect search,
code copying, themes, query examples, and navigation at mobile and desktop widths.
Use the local website assets and the guidance in `brand/README.md`; preserve font
license notices when adding fonts.
Keep generated `website/dist/`, dependency caches, and Gradle build output out of
commits. Link verification claims to the relevant CI run and its artifacts instead
of adding dated reports or source archives to the documentation. Update API, query,
compatibility, and framework guides alongside changes to their documented behavior.
