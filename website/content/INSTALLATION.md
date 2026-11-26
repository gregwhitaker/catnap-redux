# Installation

Add the Catnap integration for your framework, or a JSON binding for standalone use. Import the Catnap BOM to keep its modules on the same version. The [compatibility matrix](COMPATIBILITY.md) lists the tested framework and serializer versions; general-purpose libraries require Java 17+, and Micronaut requires Java 25.

Catnap distributes snapshots, release candidates, and stable releases through **GitHub Packages**. Choose a BOM version with a successful upload and both remote consumer checks. [Published BOM versions](https://github.com/gregwhitaker/catnap-redux/packages/3396941) · [Publication runs](https://github.com/gregwhitaker/catnap-redux/actions/workflows/publish.yml)

The November 16 snapshot predates the CATNAP query dialect and catalog examples. To try the candidate ahead of its upload, run the example from source or build the artifacts locally. [Quickstart](GETTING_STARTED.md) · [Local artifacts](#use-locally-built-artifacts) · [Snapshot verification](PUBLICATION.md#hosted-snapshot-verification)

## Choose a module

| Application | Module | Setup guide |
|---|---|---|
| Spring Boot MVC | `catnap-spring-boot-starter` | [Spring](SPRING_INTEGRATION.md) |
| Spring Boot WebFlux | `catnap-spring-boot-starter-webflux` | [Spring](SPRING_INTEGRATION.md) |
| Micronaut | `catnap-micronaut` | [Micronaut](MICRONAUT_INTEGRATION.md) |
| Quarkus REST | `catnap-quarkus` | [Quarkus](QUARKUS_INTEGRATION.md) |
| Ktor | `catnap-ktor` | [Ktor](KTOR_INTEGRATION.md) |
| Standalone Jackson 2 | `catnap-jackson2` | [JSON bindings](JSON_INTEGRATIONS.md#jackson-2-and-3) |
| Standalone Jackson 3 | `catnap-jackson3` | [JSON bindings](JSON_INTEGRATIONS.md#jackson-2-and-3) |
| Kotlin serialization | `catnap-kotlinx-json` | [JSON bindings](JSON_INTEGRATIONS.md#kotlin-serialization) |

Choose only the integration your application uses. Quarkus applications declare `catnap-quarkus`; its extension descriptor resolves the deployment artifact. Spring MVC and WebFlux have separate starters. The Catnap BOM aligns Catnap modules and imports no framework BOM; keep your framework's own dependency management.

## Consume packages from GitHub

Use `https://maven.pkg.github.com/gregwhitaker/catnap-redux` for all Catnap versions. GitHub requires authentication to download public Maven packages. For a local build, set `GITHUB_ACTOR` to your GitHub username and `GITHUB_TOKEN` to a personal access token (classic) with `read:packages` and access to the package. In GitHub Actions, a workflow with package access can pass its `GITHUB_TOKEN` to the build with `packages: read`. [GitHub Packages authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages)

## Gradle

This Kotlin DSL example installs the Spring MVC starter. It resolves Catnap modules from GitHub Packages and uses the application's usual repositories for dependencies such as Spring and Jackson.

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/gregwhitaker/catnap-redux")
                credentials {
                    username = providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
        filter { includeModuleByRegex("com[.]github[.]gregwhitaker", "catnap-.*") }
    }
}

dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-rc.1"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter")
}
```

If your application centralizes repositories, put `repositories` under `dependencyResolutionManagement` in `settings.gradle.kts`. You can set `gpr.user` and `gpr.key` in your private Gradle user properties instead of using environment variables.

## Maven

Add a server to your private `~/.m2/settings.xml`. The server ID must match the repository ID in your POM.

```xml
<settings>
    <servers>
        <server>
            <id>catnap-github</id>
            <username>${env.GITHUB_ACTOR}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

In `pom.xml`, add the repository, import the BOM, and declare the module for your application. Enabling snapshots lets the same repository serve development builds.

```xml
<properties>
    <catnap.version>3.0.0-rc.1</catnap.version>
</properties>

<repositories>
    <repository>
        <id>catnap-github</id>
        <url>https://maven.pkg.github.com/gregwhitaker/catnap-redux</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.gregwhitaker</groupId>
            <artifactId>catnap-bom</artifactId>
            <version>${catnap.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>com.github.gregwhitaker</groupId>
        <artifactId>catnap-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

## Use locally built artifacts

From the Catnap checkout, run the following command to build all 16 libraries and the BOM into `build/repository`. Local verification uses this directory and requires no GitHub credentials.

```bash
./gradlew publishVerificationArtifacts
```

Use the same dependencies as above. For Gradle, replace the `maven { ... }` block inside `forRepository` with the local directory.

```kotlin
maven {
    url = uri("/absolute/path/to/catnap/build/repository")
}
```

For Maven, replace the POM's `<repository>` element with the following. In either build tool, use the absolute path to your checkout.

```xml
<repository>
    <id>catnap-local</id>
    <url>file:///absolute/path/to/catnap/build/repository</url>
    <snapshots><enabled>true</enabled></snapshots>
</repository>
```

## Next steps

Follow your framework's setup guide, then try its [catalog walkthrough](examples/README.md#catalog-workflows). HTTP integrations default to `CATNAP` and `fields`; the [query reference](QUERY_SPECIFICATION.md) covers selection and array predicates. Maintainers publishing a new version should use the [publication guide](PUBLICATION.md) and [release checklist](RELEASING.md).
