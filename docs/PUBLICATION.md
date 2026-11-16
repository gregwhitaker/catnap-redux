# Artifact publication and verification

Catnap publishes ordinary JVM libraries under `com.github.gregwhitaker`. The current development version is `3.0.0-SNAPSHOT`. Local verification does not publish a release to a public repository.

Import the BOM and choose the integration needed by the application:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-jackson3")
    // Or catnap-spring-boot-starter, catnap-spring-boot-starter-webflux,
    // catnap-micronaut, catnap-quarkus, or catnap-ktor.
}
```

For Maven, import `catnap-bom` in `dependencyManagement` with `type=pom` and `scope=import`, then omit versions on individual Catnap dependencies. The BOM constrains exactly the libraries listed in [the publication manifest](../gradle/published-modules.txt). It imports no framework BOM and imposes no unrelated framework versions. Individual adapters declare their own required framework dependencies; an application can use its framework's supported version-management mechanism.

`catnap-testkit` is published for implementors of additional integrations. It packages the conformance fixtures and shared HTTP/streaming contract helpers. Examples and benchmarks are not published as production dependencies. The Quarkus runtime descriptor points to the separately published `catnap-quarkus-deployment` augmentation artifact; applications declare only `catnap-quarkus`.

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

The two builds keep their own test reports under `build/publication-smoke/{gradle,pom}/**/build/reports/tests`. Updating a source or publication reruns the affected work. Snapshot dependency caches are refreshed on each resolution. To publish without running consumers, use `./gradlew publishVerificationArtifacts`.

Local verification on November 16, 2026 passed `build compatibilityTest publicationSmokeTest`: 275 consumer test executions in each metadata mode (550 total), with no failures or skips, plus the main build's test matrix. The modes have separate test inputs so a Gradle-mode result cannot satisfy a POM-mode test from cache.

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

The [Publish GitHub Packages workflow](../.github/workflows/publish.yml) supports two triggers:

- **GitHub Release:** publish a release with a tag such as `v3.0.0` or `v3.0.0-rc.1`. The workflow removes the optional `v` and supplies that Maven version to both verification and publication. It does not change the development version in `gradle.properties`. Release events reject `-SNAPSHOT` versions.
- **Manual run:** choose **Actions → Publish GitHub Packages → Run workflow**, select the branch/tag to build, and enter a Maven version such as `3.0.0-SNAPSHOT`. The workflow file must be present on the repository's default branch for manual dispatch to appear.

Both paths run `build`, `compatibilityTest`, independent Gradle/POM consumers, and the five-framework streaming smoke suite at the exact publication version before uploading. The workflow serializes publication runs and uses the repository's automatic `GITHUB_TOKEN` with `contents: read` and `packages: write`. No PAT secret is needed to publish to the workflow repository. Checkout does not persist Git credentials. Verification reports and publication metadata are retained as workflow artifacts.

After uploading, separate fresh runners build and test the independent consumers against GitHub Packages in both Gradle-metadata and POM-only modes. These jobs have `packages: read`, disable Gradle cache restoration and test-output caching, and resolve Catnap exclusively from the remote registry. They verify all libraries, BOM alignment, framework applications, and core compatibility on Java 17/21/25. A successful publish step alone is insufficient: both remote-consumption jobs must also pass.

Commit and push the build configuration and workflow to the intended GitHub repository to activate them. This workspace's local checks do not deploy a workflow or publish packages. Existing packages must grant the workflow repository write access. See [GitHub's Gradle registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry) and [GitHub Actions publication guidance](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle).

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

## Hosted snapshot verification

On November 16, 2026, [publication run 43980107514](https://github.com/gregwhitaker/catnap-redux/actions/runs/43980107514) published `3.0.0-SNAPSHOT` from commit `853ef009e0389585eaa5303ed854094c6b1ca66d` to `https://maven.pkg.github.com/gregwhitaker/catnap-redux`. All 16 library publications and the BOM uploaded successfully after the full build, compatibility matrix, local publication consumers, and streaming smoke suite passed.

Fresh Ubuntu 24.04 runners then consumed the snapshot exclusively from GitHub Packages, with artifact-cache restoration and test-output caching disabled. Both modes passed all framework consumer builds, BOM alignment and dependency-boundary checks, and core tests on Java 17/21/25:

| Remote consumer mode | Tests | Failures / errors / skips |
|---|---|---|
| [Gradle module metadata](https://github.com/gregwhitaker/catnap-redux/actions/runs/43980107514/job/133700781345) | 275 | 0 / 0 / 0 |
| [Maven POM only](https://github.com/gregwhitaker/catnap-redux/actions/runs/43980107514/job/133700781362) | 275 | 0 / 0 / 0 |

The workflow's `github-packages-consumers-gradle` and `github-packages-consumers-pom` artifacts contain the JUnit XML and HTML reports for these 550 remote-consumer test executions. This evidence covers the published development snapshot; signing and native-image verification retain their separately documented scope.

## Consume packages from GitHub

GitHub's Maven registry requires authentication even for public packages. Consumers outside the publishing workflow normally use a classic PAT with `read:packages` and access to the repository. Add the registry to the consuming application's repositories, alongside Maven Central:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/gregwhitaker/catnap-redux")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        }
        content { includeGroup("com.github.gregwhitaker") }
    }
}
```

For builds that centralize repositories, put the block under `dependencyResolutionManagement` in `settings.gradle.kts`. Then use the BOM and dependencies shown above, substituting a version that has actually been published.

Maven consumers use the same URL in a repository with an ID such as `github`, enable snapshots when using `-SNAPSHOT`, and configure the matching `server` credentials in their private `~/.m2/settings.xml`. Package authentication is separate from the BOM's `dependencyManagement` import.
