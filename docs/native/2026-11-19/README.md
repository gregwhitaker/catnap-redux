# Native verification — November 19, 2026

Quarkus and Micronaut passed their native suites with the CATNAP default, RSQL-subset array predicates, and catalog examples. Both executables were rebuilt using Oracle GraalVM 25.0.4+7.1 on macOS 15.7.4/AArch64 with Apple Clang 17.0.0, a 6 GiB compiler heap, four compiler threads, and quick-build optimization. This verifies correctness on that platform; it does not measure native performance or verify Linux.

| Framework | Execution boundary | Tests | Failures | Errors | Skipped | Evidence |
|---|---|---:|---:|---:|---:|---|
| Quarkus 3.40.1.2 | JVM HTTP tests against the external native server | 160 | 0 | 0 | 0 | [Build and test log](quarkus/gradle.log), seven JUnit XML reports in `quarkus/` |
| Micronaut platform 5.2.6, core 5.2.23 | Application and JUnit HTTP tests compiled into one native executable | 152 | 0 | 0 | 0 | [Build and test log](micronaut/gradle.log), [JUnit XML](micronaut/TEST-junit-jupiter.xml) |

Each framework passes 52 catalog cases covering product detail, paginated search, realistic seed-data loading, serializer visibility, image/variant predicates, empty matches, invalid queries, and validation errors. The remaining cases cover configured CATNAP and LinkedIn dialects, renamed query parameters, finite publishers, NDJSON/SSE contracts, serializer context, limits, early delivery, slow consumers, cancellation, and disablement.

The first Quarkus run passed its 128 existing native tests. Review found that `CatnapResponseTest` lacked a native integration-test entrypoint. Adding `CatnapResponseIT` brings its existing 32 configured-CATNAP tests into the native suite, including renamed parameters and malformed predicates. The final rebuilt Quarkus executable passed all 160 tests. Micronaut passed all 152 tests on its first native run. No production changes or relaxed assertions were needed.

## Source and evidence

The source baseline is commit `62eaeb0f7e262e3a4a9f34de55617dae4c80a6eb`, plus the new Quarkus test entrypoint. The [summary](summary.json) records the exact commands, toolchain, compiler-archive checksum, executable hashes, per-class counts, evidence hashes, and per-file source hashes. The [source snapshot](source-snapshot.zip) includes source code, runtime resources, build declarations, the wrapper, and dependency locks. Generated executables and downloaded dependencies are not included. The [November 16 evidence](../2026-11-16/README.md) remains unchanged and describes its earlier source snapshot.

## Reproduce

Set `GRAALVM_HOME` to an installed GraalVM 25 JDK home with `native-image`, then run from the repository root:

```bash
./gradlew :examples:quarkus:testNative \
  -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.native-image-xmx=6g \
  -Dquarkus.native.additional-build-args=--parallelism=4,-Ob \
  --console=plain

./gradlew :examples:micronaut:nativeTestCompile \
  --quick-build-native --jvm-args-native=-Xmx6g --build-args=--parallelism=4 \
  :examples:micronaut:nativeTest --console=plain
```

See the [native guide](../../../website/content/NATIVE_IMAGES.md) for application-model metadata, process boundaries, and smaller-worker settings. These results cover the two native suites above; additional operating systems and framework native integrations need their own execution evidence.
