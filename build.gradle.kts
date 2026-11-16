plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin.jvm) apply false
}

apply(from = "gradle/publication-verification.gradle.kts")

val exampleProjects =
    listOf("spring-boot-mvc", "spring-boot-webflux", "micronaut", "quarkus", "ktor")
val streamingBenchmarkProjects =
    listOf(
        "streaming-runner",
        "server-mvc",
        "server-webflux",
        "server-micronaut",
        "server-quarkus",
        "server-ktor",
    )
val libraryProjects =
    listOf(
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
        "catnap-spring-boot-autoconfigure",
        "catnap-spring-boot-starter",
        "catnap-spring-webflux",
        "catnap-spring-boot-starter-webflux",
    )

spotless {
    kotlinGradle {
        target(
            "*.gradle.kts",
            "build-logic/*.gradle.kts",
            "build-logic/*/*.gradle.kts",
            "build-logic/*/src/**/*.gradle.kts",
            "catnap-bom/*.gradle.kts",
            "gradle/*.gradle.kts",
            "verification/**/*.gradle.kts",
        )
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlin {
        target("verification/**/src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    java {
        target("verification/**/src/**/*.java")
        googleJavaFormat(libs.versions.google.java.format.get())
        removeUnusedImports()
    }
}

tasks.named("assemble") {
    dependsOn(streamingBenchmarkProjects.map { ":benchmarks:$it:assemble" })
    dependsOn(":benchmarks:assemble")
    dependsOn(":benchmarks:streaming-fixture:assemble")
    dependsOn(":catnap-bom:assemble")
    dependsOn(libraryProjects.map { ":$it:assemble" })
    dependsOn(":examples:standalone:assemble")
    dependsOn(
        ":catnap-core:assemble",
        ":catnap-testkit:assemble",
        ":examples:jackson3-probe:assemble",
    )
    dependsOn(exampleProjects.map { ":examples:$it:assemble" })
}

tasks.named("check") {
    dependsOn(streamingBenchmarkProjects.map { ":benchmarks:$it:check" })
    dependsOn(":benchmarks:check")
    dependsOn(":benchmarks:streaming-fixture:check")
    dependsOn(":catnap-bom:check")
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(libraryProjects.map { ":$it:check" })
    dependsOn(":examples:standalone:check")
    dependsOn(":catnap-core:check", ":catnap-testkit:check", ":examples:jackson3-probe:check")
    dependsOn(exampleProjects.map { ":examples:$it:check" })
}

tasks.register("streamingTest") {
    group = "verification"
    description = "Runs framework HTTP tests, including NDJSON/SSE contracts and lifecycle checks."
    dependsOn(exampleProjects.map { ":examples:$it:test" })
}

tasks.register("compatibilityTest") {
    group = "verification"
    description = "Loads the core artifact and runs its tests on Java 17, 21, and 25."
    dependsOn(listOf(17, 21, 25).map { ":catnap-core:testJava$it" })
}

tasks.register("lockDependencies") {
    dependsOn(streamingBenchmarkProjects.map { ":benchmarks:$it:resolveDependencies" })
    dependsOn(":benchmarks:resolveDependencies")
    dependsOn(":benchmarks:streaming-fixture:resolveDependencies")
    dependsOn(libraryProjects.map { ":$it:resolveDependencies" })
    dependsOn(":examples:standalone:resolveDependencies")
    group = "build setup"
    description = "Resolves all project configurations; run with --write-locks."
    dependsOn(
        ":catnap-core:resolveDependencies",
        ":catnap-testkit:resolveDependencies",
        ":examples:jackson3-probe:resolveDependencies",
    )
    dependsOn(exampleProjects.map { ":examples:$it:resolveDependencies" })
}

tasks.wrapper {
    gradleVersion = "9.7.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"
}

// The build loads Micronaut's Java 25 plugin before project compilation toolchains apply.
tasks.updateDaemonJvm { languageVersion = JavaLanguageVersion.of(25) }
