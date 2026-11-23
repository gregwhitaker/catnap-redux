# Publishing artifacts

This guide is for maintainers verifying and publishing Catnap artifacts. To add Catnap to an application, use [installation](INSTALLATION.md). Follow the [release checklist](RELEASING.md) to prepare a candidate and track what has actually been verified.

## Publication contents

Catnap publishes under `com.github.gregwhitaker`. The [publication manifest](https://github.com/gregwhitaker/catnap-redux/blob/main/gradle/published-modules.txt) lists 16 libraries and the BOM. The BOM aligns Catnap versions and imports no framework BOM. Examples, benchmarks, and build conventions are not production publications.

`catnap-testkit` packages conformance fixtures and shared HTTP/streaming contracts for integration authors. The Quarkus runtime descriptor identifies the separate `catnap-quarkus-deployment` artifact; applications declare only `catnap-quarkus`.

## Local release verification

Use the checked-in wrapper; its daemon configuration selects Java 25, with separate Java 17/21/25 compilation and test toolchains:

```bash
./gradlew publicationSmokeTest
```

The task:

1. Clears the generated `build/repository` directory and publishes all libraries and the BOM there. It neither reads nor writes Maven Local.
2. Copies independent consumer build scripts and example application sources into `build/publication-smoke/gradle` and `build/publication-smoke/pom`. These builds have no included build, Catnap project dependencies, or project substitution. Catnap coordinates resolve exclusively from that temporary repository.
3. Builds and tests the standalone and framework applications against the published jars, including finite-response and NDJSON/SSE lifecycle contracts. It runs the published core on Java 17, 21, and 25, and exercises Jackson 2, Jackson 3, and Kotlin serialization bindings.
4. Repeats consumption using only Maven POM metadata, with Gradle metadata redirection disabled. This verifies both publication formats, including Kotlin JVM transitive dependencies and Quarkus augmentation.
5. Inspects every POM, Gradle module file, source jar, documentation jar, bytecode baseline, and framework registration descriptor. Each documentation variant must contain one artifact with matching size and SHA-256 metadata. It checks BOM contents, versionless consumer dependencies, and runtime boundaries, including an MVC application without Reactor and a Quarkus runtime without augmentation dependencies.

The copied examples intentionally reuse the shared contract tests. Their Catnap implementation, bindings, testkit, auto-configuration, and extension deployment code all come from published artifacts. Java 17 consumers establish the ordinary library/runtime baseline; Micronaut 5 consumers run on Java 25. JVM tests do not prove native-image compatibility.

Consumers compile all three dialects, evaluate CATNAP predicates through a consumer-owned tree adapter and all three published JSON bindings, and read the predicate corpus from the published testkit JAR. They also verify that the published HTTP defaults enable CATNAP predicates and that the example applications accept predicates without a dialect override. Google and LinkedIn remain explicit options.

The two builds keep their own test reports under `build/publication-smoke/{gradle,pom}/**/build/reports/tests`. Updating a source or publication reruns the affected work. Snapshot dependency caches are refreshed on each resolution. To publish without running consumers, use `./gradlew publishVerificationArtifacts`.

See the [release checklist](RELEASING.md#verification-status) for the source covered by the latest checks.

## Source and documentation artifacts

Java modules attach standard Javadoc and source jars. Kotlin modules attach [Dokka HTML](https://kotlinlang.org/docs/dokka-gradle.html) under Maven's `javadoc` classifier; these include Kotlin classes, extensions, and signatures. Dokka is pinned in the version catalog and uses offline mode for deterministic external-link handling. Dependency-only Boot starters contain a guide and documentation index because they define no Java API.

## Signing and release publication

Ordinary builds and local verification need no signing credentials. To exercise release signing separately:

```bash
./gradlew publishVerificationArtifacts -Pversion=3.0.0 -PreleaseSigning=true
```

Supply Gradle Signing Plugin properties (`signing.keyId`, `signing.secretKeyRingFile`, and `signing.password`) from your private Gradle user properties or CI secrets. Never store them in this repository. With `releaseSigning=true`, publication depends on signing and fails if the credentials are unavailable; an unsigned artifact cannot silently satisfy that requested signing step. See the [Gradle signing documentation](https://docs.gradle.org/9.7.0/userguide/signing_plugin.html).

GitHub Packages publication is configured alongside the temporary local repository. Signing remains optional and separate from registry authentication; the GitHub workflow uses unsigned Maven publications by default. The full release gate also includes benchmarks, compatibility documentation, and any native-image support claimed by the release; passing publication tests alone is insufficient.

## Publish to GitHub Packages

The shared publishing convention registers `GitHubPackages` at `https://maven.pkg.github.com/gregwhitaker/catnap-redux`. The root `publishGitHubPackages` task publishes the same 16 libraries and BOM listed in the manifest, with Maven POMs, Gradle module metadata, binary jars, sources, and API documentation as applicable. Examples, benchmark projects, and build conventions are excluded.

The [Publish GitHub Packages workflow](https://github.com/gregwhitaker/catnap-redux/blob/main/.github/workflows/publish.yml) supports two triggers:

- **GitHub Release:** publish a release with a tag such as `v3.0.0` or `v3.0.0-rc.1`. The workflow removes the optional `v` and supplies that Maven version to both verification and publication. It does not change the development version in `gradle.properties`. Release events reject `-SNAPSHOT` versions.
- **Manual run:** choose **Actions → Publish GitHub Packages → Run workflow**, select the branch/tag to build, and enter a Maven version such as `3.0.0-SNAPSHOT`. The workflow file must be present on the repository's default branch for manual dispatch to appear.

Both paths run `build`, `compatibilityTest`, independent Gradle/POM consumers, and the five-framework streaming smoke suite at the exact publication version before uploading. The workflow serializes publication runs and uses the repository's automatic `GITHUB_TOKEN` with `contents: read` and `packages: write`. No PAT secret is needed to publish to the workflow repository. Checkout does not persist Git credentials. Verification reports and publication metadata are retained as workflow artifacts.

After uploading, separate fresh runners build and test the independent consumers against GitHub Packages in both Gradle-metadata and POM-only modes. These jobs have `packages: read`, disable Gradle cache restoration and test-output caching, and resolve Catnap exclusively from the remote registry. They verify all libraries, BOM alignment, framework applications, and core compatibility on Java 17/21/25. A successful publish step alone is insufficient: both remote-consumption jobs must also pass.

Existing packages must grant the workflow repository write access. Local verification does not upload packages; publication runs only through the explicit task or workflow described here. See [GitHub's Gradle registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry) and [GitHub Actions publication guidance](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle).

For local publication, supply a classic PAT with `write:packages` and access to the destination repository through private Gradle user properties or environment variables:

| Gradle user property | Environment fallback | Purpose |
|---|---|---|
| `gpr.user` | `GITHUB_ACTOR` | GitHub username |
| `gpr.key` | `GITHUB_TOKEN` | PAT locally; automatic workflow token in Actions |
| `githubRepository` | `GITHUB_REPOSITORY` | Destination `owner/repository`; defaults to `gregwhitaker/catnap-redux` locally |

Once credentials are available:

```bash
./gradlew publishGitHubPackages -Pversion=3.0.0-SNAPSHOT
# Override the destination when publishing from a local checkout to another repository:
./gradlew publishGitHubPackages -Pversion=3.0.0-SNAPSHOT -PgithubRepository=owner/repository
```

Keep credentials in your private `~/.gradle/gradle.properties` or a secret-managed environment, never project files or command-line token arguments. A destination override uses the same GitHub Maven host and normalizes the repository name to lowercase. Ordinary `build` and `publicationSmokeTest` need no package credentials. `publishVerificationArtifacts` still writes only to `build/repository`; `publishGitHubPackages` targets only the remote registry. Plain `publish` targets every configured publication repository.

Use a new non-SNAPSHOT version for each release. Maven uploads happen per artifact, so a failed run can leave a partially published set; inspect the failed step and package versions before retrying. Snapshots can be published repeatedly. Timed benchmarks and native-image checks remain separately recorded release evidence rather than work repeated by every snapshot deployment.

## Recorded local verification

Local verification on November 16, 2026 passed `build compatibilityTest publicationSmokeTest`: 275 consumer test executions in each metadata mode (550 total), with no failures or skips, plus the main build's test matrix. The modes have separate test inputs so a Gradle-mode result cannot satisfy a POM-mode test from cache.

Verification on November 18, 2026 for the CATNAP dialect and default change passed `build compatibilityTest streamingTest publicationSmokeTest`: 733 consumer test executions in each metadata mode (1,466 total), with no failures, errors, or skips. This is JVM and local-publication evidence; it does not update the earlier timed benchmark or native-image results.

## Hosted snapshot verification

On November 16, 2026, [publication run 43980107514](https://github.com/gregwhitaker/catnap-redux/actions/runs/43980107514) published `3.0.0-SNAPSHOT` from commit `853ef009e0389585eaa5303ed854094c6b1ca66d` to `https://maven.pkg.github.com/gregwhitaker/catnap-redux`. All 16 library publications and the BOM uploaded successfully after the full build, compatibility matrix, local publication consumers, and streaming smoke suite passed.

Fresh Ubuntu 24.04 runners then consumed the snapshot exclusively from GitHub Packages, with artifact-cache restoration and test-output caching disabled. Both modes passed all framework consumer builds, BOM alignment and dependency-boundary checks, and core tests on Java 17/21/25:

| Remote consumer mode | Tests | Failures / errors / skips |
|---|---|---|
| [Gradle module metadata](https://github.com/gregwhitaker/catnap-redux/actions/runs/43980107514/job/133700781345) | 275 | 0 / 0 / 0 |
| [Maven POM only](https://github.com/gregwhitaker/catnap-redux/actions/runs/43980107514/job/133700781362) | 275 | 0 / 0 / 0 |

The workflow's `github-packages-consumers-gradle` and `github-packages-consumers-pom` artifacts contain the JUnit XML and HTML reports for these 550 remote-consumer test executions. This upload predates the CATNAP dialect and catalog examples. It verifies that older snapshot, not the current checkout; a new candidate needs both remote-consumer jobs to pass. Signing and native-image verification retain their separately documented scope.

## Use locally built artifacts

The [local installation instructions](INSTALLATION.md#use-locally-built-artifacts) explain how to publish the checkout to `build/repository` and configure an independent application. They include complete [Gradle](INSTALLATION.md#gradle) and [Maven](INSTALLATION.md#maven) examples.

## Consume packages from GitHub

See [GitHub Packages installation](INSTALLATION.md#consume-packages-from-github) for the repository declaration and consumer credentials. Choose a published version whose source includes the features needed by the application; the recorded snapshot above predates CATNAP predicates.
