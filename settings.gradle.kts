pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "catnap"

include(
    "benchmarks",
    "benchmarks:streaming-fixture",
    "benchmarks:streaming-runner",
    "benchmarks:server-mvc",
    "benchmarks:server-webflux",
    "benchmarks:server-micronaut",
    "benchmarks:server-quarkus",
    "benchmarks:server-ktor",
    "catnap-core",
    "catnap-bom",
    "catnap-testkit",
    "catnap-jackson2",
    "catnap-jackson3",
    "catnap-kotlinx-json",
    "catnap-streams",
    "catnap-http",
    "catnap-micronaut",
    "catnap-ktor",
    "catnap-quarkus",
    "catnap-quarkus-deployment",
    "catnap-spring-webmvc",
    "catnap-spring-webflux",
    "catnap-spring-boot-autoconfigure",
    "catnap-spring-boot-starter",
    "catnap-spring-boot-starter-webflux",
    "examples:standalone",
    "examples:jackson3-probe",
    "examples:spring-boot-mvc",
    "examples:spring-boot-webflux",
    "examples:micronaut",
    "examples:quarkus",
    "examples:ktor",
)

project(":catnap-quarkus").projectDir = file("catnap-quarkus/runtime")

project(":catnap-quarkus-deployment").projectDir = file("catnap-quarkus/deployment")
