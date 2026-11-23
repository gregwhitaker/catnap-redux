# Native-image verification

Native tests are separate from ordinary `check`. They exercise the example applications' finite JSON, NDJSON, and JSON-bearing SSE contracts, including configured serializers and dialects, limits, early delivery, event metadata, slow consumers, and cancellation. The native executable is the server under test; JVM augmentation or successful compilation alone does not satisfy this check.

Both suites cover the CATNAP default and its array predicates, configured dialects, and the catalog examples. Run them for the source revision and platforms you intend to support.

## Toolchain and commands

Install GraalVM for JDK 25 with `native-image` and a supported C toolchain. Set `GRAALVM_HOME` to its JDK home. Gradle still needs Java 25 and the ordinary Java 17/21/25 toolchains described in the [compatibility matrix](COMPATIBILITY.md). Micronaut explicitly resolves its native compiler from `GRAALVM_HOME`, independently of its ordinary Java toolchain.

```bash
# Build Quarkus's native server, then run its existing HTTP contracts against that process.
./gradlew :examples:quarkus:testNative \
  -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.native-image-xmx=4g \
  -Dquarkus.native.additional-build-args=--parallelism=2,-Ob

# Compile and run Micronaut's complete example test suite inside a native executable.
./gradlew :examples:micronaut:nativeTestCompile \
  --quick-build-native --jvm-args-native=-Xmx4g --build-args=--parallelism=2 \
  :examples:micronaut:nativeTest
```

The resource limits above suit a small CI worker; adjust heap and compiler threads to the available resources. Quick-build mode reduces compiler optimization and is appropriate for correctness verification. These native tests are not native-performance measurements. Quarkus must disable JAR packaging for this native invocation; ordinary JVM builds retain their normal packaging.

These commands use [Quarkus's native integration-test task](https://quarkus.io/guides/gradle-tooling/#running-native-tests) and [Micronaut's native JUnit support](https://guides.micronaut.io/latest/micronaut-graalvm-reflection-gradle-java.html). The [Native Build Tools documentation](https://graalvm.github.io/native-build-tools/latest/gradle-plugin) describes compiler discovery and configuration.

## Shared contracts across process boundaries

Quarkus's `src/integrationTest` classes inherit the corresponding JVM HTTP tests. Its native task includes that source set and starts the built executable using `@QuarkusIntegrationTest`. Test profiles configure runtime dialect, parameter names, limits, and disablement exactly as in the JVM suite. Keep an integration-test entrypoint for each HTTP test class: the catalog tests exercise the default CATNAP dialect, while `CatnapResponseIT` additionally verifies CATNAP with a renamed parameter and disabled query cache.

The example exposes `/test-control/stream/{key}` and finite-publisher observation endpoints so external tests can release source gates and observe production/cancellation. These controls belong only to the example application. They are not installed by the Catnap extension. The shared streaming contract uses in-memory controls for in-process JVM tests and HTTP controls for the separate Quarkus native process; its assertions remain the same. Timed-out reader cleanup closes the underlying HTTP body before waiting for the buffered reader's lock.

Micronaut's `nativeTest` compiles both the application and its current example test suite into one executable. Its shared source gates therefore remain in process, as they are during ordinary JVM tests.

## Application model metadata

Native-image applications must make their own serialization types reachable using their framework's metadata mechanisms. Quarkus can infer many types from endpoint signatures, but objects serialized manually behind a `JsonNode`, response wrapper, or explicit streaming callback may require registration. Use `@RegisterForReflection` for model types that inference cannot discover. Retain Jackson's empty-bean failure so missing serialization metadata remains visible.

Micronaut's examples use generated serialization metadata for their domain models. Catnap's core does not add reflection requirements to its query AST or generic tree evaluator. Native support for one application model does not make arbitrary application serializers or libraries native-compatible.

## Reports and CI

Local test reports are written under each example's `build/test-results/` and `build/reports/tests/` directories. Keep reports, compiler logs, executable hashes, and any source snapshots in ignored build output or CI artifacts. When sharing results, identify the source commit, actual GraalVM version, C toolchain, operating system, and architecture.

The [Build workflow](https://github.com/gregwhitaker/catnap-redux/blob/main/.github/workflows/ci.yml) has a manual `native_images` input that enables independent Quarkus and Micronaut jobs after ordinary verification. It installs GraalVM 25, prints the actual compiler version, and uploads test reports as `native-quarkus-reports` and `native-micronaut-reports` artifacts. An ordinary push skips these native jobs.

A passing run covers its tested application models and environment. These two jobs do not establish native execution for Spring MVC, WebFlux, Ktor, or other operating systems.
