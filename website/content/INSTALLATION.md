# Installation

Add the Catnap integration for your framework, or a JSON binding for standalone use. Import the Catnap BOM to keep its modules on the same version. The [compatibility matrix](COMPATIBILITY.md) lists the tested framework and serializer versions; general-purpose libraries require Java 17+, and Micronaut requires Java 25.

These guides describe the `3.0.0-SNAPSHOT` source checkout. **Build local artifacts to use the current CATNAP dialect and catalog examples.** The recorded GitHub Packages snapshot was published on November 16, before those changes. A shared snapshot version does not mean that a published artifact matches this checkout. See the [hosted snapshot verification](PUBLICATION.md#hosted-snapshot-verification) for that publication's source revision and CI results.

To try Catnap before adding a dependency, follow the [quickstart](GETTING_STARTED.md); it runs directly from source.

## Use locally built artifacts

From the Catnap repository root, run:

```bash
./gradlew publishVerificationArtifacts
```

This writes the libraries and BOM to `build/repository`. It does not upload packages or write to Maven Local. Java 17+ can launch the wrapper; Gradle selects a Java 25 build daemon and can provision the required toolchains.

In your application, configure this directory as a Maven repository. Replace the absolute path in the Gradle or Maven example below with your checkout's location. Rebuild the artifacts after changing Catnap's source.

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

## Gradle

This example installs the Spring MVC starter. Substitute a module from the table for other frameworks or standalone use:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("/absolute/path/to/catnap/build/repository")
        content { includeGroup("com.github.gregwhitaker") }
    }
}

dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter")
}
```

If your application centralizes repositories, put the repository declaration in `settings.gradle.kts` under `dependencyResolutionManagement`. Framework dependencies continue resolving from their normal repositories.

## Maven

Configure the local repository, import the BOM, and declare the selected module without a version:

```xml
<repositories>
    <repository>
        <id>catnap-local</id>
        <url>file:///absolute/path/to/catnap/build/repository</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.gregwhitaker</groupId>
            <artifactId>catnap-bom</artifactId>
            <version>3.0.0-SNAPSHOT</version>
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

## Consume packages from GitHub

Use this repository for a version that has been published and verified for the features you need. The [recorded upload](PUBLICATION.md#hosted-snapshot-verification) identifies its source commit. GitHub's Maven registry requires authentication even for public packages; consumers outside the publishing workflow normally use a classic PAT with `read:packages` and repository access.

Replace the local repository declaration with:

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

Keep credentials in private Gradle user properties or environment variables. Use the BOM and dependencies above with the verified publication version. Maven consumers use the same URL with a repository ID such as `github`, enable snapshots for a `-SNAPSHOT` version, and configure matching server credentials in private `~/.m2/settings.xml`.

## Next steps

Follow your framework's setup guide, then try its [catalog walkthrough](examples/README.md#catalog-workflows). HTTP integrations default to `CATNAP` and `fields`; the [query reference](QUERY_SPECIFICATION.md) covers selection and array predicates. Maintainers publishing a new version should use the [publication guide](PUBLICATION.md) and [release checklist](RELEASING.md).
