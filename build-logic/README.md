# Catnap build conventions

This included Gradle build holds the convention plugins used by Catnap's libraries, examples, and benchmarks. It centralizes toolchains, formatting, testing, publication, and dependency locking. These plugins are build infrastructure and are not published as Catnap application dependencies.

Each convention plugin has its own project, source, generated plugin descriptors, dependencies, and lockfile. The main build includes this directory through `pluginManagement` in [settings.gradle.kts](../settings.gradle.kts).

## Plugins

| Project | Plugin ID | Direct plugin dependencies |
|---|---|---|
| `jvm-conventions` | `catnap.jvm-conventions` | Spotless |
| `java-library` | `catnap.java-library` | `jvm-conventions` |
| `kotlin-library` | `catnap.kotlin-library` | `java-library`, Kotlin JVM, Spotless, Dokka |
| `testing` | `catnap.testing` | `jvm-conventions` |
| `publishing` | `catnap.publishing` | `java-library` |
| `benchmark-server` | `catnap.benchmark-server` | Gradle built-in APIs only |

The root project aggregates `assemble`, `check`, and `lockDependencies` and contains no plugin implementation. Versions are imported from the main build's [version catalog](../gradle/libs.versions.toml).

## Build and check

Run commands from the repository root using the wrapper and [documented JDK setup](../README.md#building-from-source):

```bash
./gradlew -p build-logic build
./gradlew spotlessCheck
```

Formatting is checked by the main build's root Spotless configuration, which includes every plugin subproject's build script and source script.

The JVM convention sets Java 17 as the default compilation baseline, checks production bytecode, configures JUnit Platform test execution, and creates reproducible archives. Individual integrations can declare a different toolchain, such as Micronaut's Java 25 requirement. The build daemon's Java version is separate from each library's target.

## Publication and benchmarks

The publishing convention supports both Java libraries and a `java-platform` applied before it. Kotlin libraries use Dokka HTML in their `javadoc` classifier; Java libraries use standard Javadoc. See [publication verification](../docs/PUBLICATION.md).

The Kotlin convention retains Java's `sourcesJar`, which includes Kotlin sources, and disables the pinned Kotlin plugin's competing writer. Its sources variant publishes one artifact; publication verification checks documentation artifact counts, sizes, and SHA-256 hashes.

The publishing convention configures separate local-verification and GitHub Packages destinations. Package credentials are supplied through private Gradle properties or environment variables; normal build and local-verification tasks do not require them. The root publication manifest drives both local verification and the explicit `publishGitHubPackages` aggregate.

The benchmark-server convention is applied after Java/application or Quarkus plugins. It writes the Java 25 launch specification used by the HTTP streaming measurement runner. It does not add framework dependencies or publish an artifact.

## Updating build tooling

Change shared versions in the version catalog and direct plugin dependencies in the relevant project's build script. Refresh the included build's locks explicitly:

```bash
./gradlew -p build-logic lockDependencies --write-locks
./gradlew -p build-logic build
./gradlew check
```

Review the resulting lockfile changes. The main build's `lockDependencies` task resolves application/library configurations; it does not replace this included build's lock task. For changes to publication behavior, also run `./gradlew publicationSmokeTest` to verify independent consumers and artifact metadata.
