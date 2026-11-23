# Native-image verification

Native tests are separate from ordinary `check`. They exercise the example applications' finite JSON, NDJSON, and JSON-bearing SSE contracts, including configured serializers and dialects, limits, early delivery, event metadata, slow consumers, and cancellation. The native executable is the server under test; JVM augmentation or successful compilation alone does not satisfy this check.

The [November 19 native-image results](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/native/2026-11-19/README.md) verify the CATNAP default and its array predicates, configured CATNAP/LinkedIn dialects, and the catalog examples on macOS AArch64. The [November 16 baseline](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/native/2026-11-16/README.md) remains archived separately; it predates CATNAP predicates.

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

The resource limits above suit a small CI worker; the recorded local run used a 6 GiB compiler heap and four compiler threads. Quick-build mode reduces compiler optimization and is appropriate for correctness verification. These native tests are not native-performance measurements. Quarkus must disable JAR packaging for this native invocation; ordinary JVM builds retain their normal packaging.

These commands use [Quarkus's native integration-test task](https://quarkus.io/guides/gradle-tooling/#running-native-tests) and [Micronaut's native JUnit support](https://guides.micronaut.io/latest/micronaut-graalvm-reflection-gradle-java.html). The [Native Build Tools documentation](https://graalvm.github.io/native-build-tools/latest/gradle-plugin) describes compiler discovery and configuration.

## Shared contracts across process boundaries

Quarkus's `src/integrationTest` classes inherit the corresponding JVM HTTP tests. Its native task includes that source set and starts the built executable using `@QuarkusIntegrationTest`. Test profiles configure runtime dialect, parameter names, limits, and disablement exactly as in the JVM suite. Keep an integration-test entrypoint for each HTTP test class: the catalog tests exercise the default CATNAP dialect, while `CatnapResponseIT` additionally verifies CATNAP with a renamed parameter and disabled query cache.

The example exposes `/test-control/stream/{key}` and finite-publisher observation endpoints so external tests can release source gates and observe production/cancellation. These controls belong only to the example application. They are not installed by the Catnap extension. The shared streaming contract uses in-memory controls for in-process JVM tests and HTTP controls for the separate Quarkus native process; its assertions remain the same. Timed-out reader cleanup closes the underlying HTTP body before waiting for the buffered reader's lock.

Micronaut's `nativeTest` compiles both the application and its current example test suite into one executable. Its shared source gates therefore remain in process, as they are during ordinary JVM tests.

## Application model metadata

Native-image applications must make their own serialization types reachable using their framework's metadata mechanisms. Quarkus can infer many types from endpoint signatures, but objects serialized manually behind a `JsonNode`, response wrapper, or explicit streaming callback may require registration. Use `@RegisterForReflection` for model types that inference cannot discover. Retain Jackson's empty-bean failure so missing serialization metadata remains visible.

Micronaut's examples use generated serialization metadata for their domain models. Catnap's core does not add reflection requirements to its query AST or generic tree evaluator. Native support for one application model does not make arbitrary application serializers or libraries native-compatible.

## Evidence and CI

Local verification uses Oracle GraalVM `25.0.4+7.1` (`25.0.4+7-LTS-jvmci-b01`) on macOS 15.7.4/aarch64 with Apple Clang 17.0.0. The downloaded macOS AArch64 archive has SHA-256 `0b79e23c133facbad2f7aa55a3b76d17bd59d2fa15e2735bb63391ace223fd13`; the installed version is recorded rather than inferred from the moving download URL.

The [November 19 native report](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/native/2026-11-19/README.md) contains 160 passing Quarkus tests and 152 passing Micronaut tests, with no failures, errors, or skipped tests, plus logs, compiler details, executable hashes, and a source snapshot. Each framework passes all 52 catalog cases, including product detail, paginated search, and image/variant predicates. Those dated results describe commit `62eaeb0` plus the recorded Quarkus native-test entrypoint; rerun the commands above after source changes. No native execution claim is made for Spring MVC, WebFlux, or Ktor by these two jobs; their JVM matrix remains separately verified.

The manual `native_images` workflow input enables independent Quarkus and Micronaut jobs after ordinary verification. It installs GraalVM 25, prints the actual compiler version, and preserves test reports. That workflow has been validated locally as YAML; a hosted Linux run has not been executed from this workspace.
