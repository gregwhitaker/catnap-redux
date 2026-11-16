plugins {
    id("catnap.java-library")
    id("catnap.testing")
    id("catnap.publishing")
}

dependencies {
    testImplementation(project(":catnap-testkit"))
    testImplementation(libs.fixture.json)
}

listOf(17, 21, 25).forEach { runtime ->
    tasks.register<Test>("testJava$runtime") {
        group = "verification"
        description = "Tests the Java 17 core artifact on Java $runtime."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        javaLauncher =
            javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(runtime) }
    }
}

tasks.register("verifyCoreDependencies") {
    group = "verification"
    doLast {
        check(
            configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.isEmpty()
        ) {
            "The core must have no third-party runtime dependencies."
        }
    }
}

tasks.named("check") { dependsOn("verifyCoreDependencies") }
