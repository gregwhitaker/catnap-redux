import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

val publishedModules = file("gradle/published-modules.txt").readLines().filter { it.isNotBlank() }

tasks.register("publishGitHubPackages") {
    group = "publishing"
    description = "Publishes all Catnap libraries and the BOM to GitHub Packages."
    dependsOn(
        (publishedModules + "catnap-bom").map {
            ":$it:publishMavenJavaPublicationToGitHubPackagesRepository"
        }
    )
}

val repository = layout.buildDirectory.dir("repository")
val prepareRepository =
    tasks.register<Delete>("preparePublicationRepository") { delete(repository) }

// Every verification run starts from an empty repository, so stale snapshots cannot hide omissions.
subprojects {
    plugins.withId("maven-publish") {
        tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToLocalVerificationRepository")) dependsOn(prepareRepository)
        }
    }
}

val publishVerificationArtifacts =
    tasks.register("publishVerificationArtifacts") {
        group = "publishing"
        description =
            "Publishes the complete distribution to a fresh temporary local Maven repository."
        dependsOn(
            (publishedModules + "catnap-bom").map {
                ":$it:publishMavenJavaPublicationToLocalVerificationRepository"
            }
        )
    }

val consumerModes = listOf("gradle", "pom")
val smokeTasks =
    consumerModes.map { mode ->
        val destination = layout.buildDirectory.dir("publication-smoke/$mode")
        val prepare =
            tasks.register<Sync>("prepare${mode.replaceFirstChar(Char::uppercaseChar)}Consumers") {
                from("verification/publication")
                from("gradle/libs.versions.toml") { into("gradle") }
                from("gradle/published-modules.txt") { into("gradle") }
                from("gradle/gradle-daemon-jvm.properties") { into("gradle") }
                listOf(
                        "standalone",
                        "jackson3-probe",
                        "spring-boot-mvc",
                        "spring-boot-webflux",
                        "micronaut",
                        "quarkus",
                        "ktor",
                    )
                    .forEach { example ->
                        from("examples/$example/src") { into("examples/$example/src") }
                    }
                into(destination)
                // Preserve only generated build state. Source removals must still propagate to the
                // fixture.
                preserve { include("**/build/**", ".gradle/**", ".kotlin/**") }
            }
        tasks.register<Exec>("publication${mode.replaceFirstChar(Char::uppercaseChar)}SmokeTest") {
            group = "verification"
            description = "Builds independent consumers using only published ${mode} metadata."
            dependsOn(publishVerificationArtifacts, prepare)
            commandLine(
                rootProject.file("gradlew").absolutePath,
                "-p",
                destination.get().asFile.absolutePath,
                "--no-daemon",
                "--console=plain",
                "-PcatnapVersion=${project.version}",
                "-PcatnapRepository=${repository.get().asFile.absolutePath}",
                "-PmetadataMode=$mode",
                "build",
                "verifyPublicationMetadata",
                "verifyPublishedDependencies",
                "compatibilityTest",
            )
            // Explicit toolchain locations from the outer invocation must reach the independent
            // JVMs.
            listOf("fromEnv", "paths", "auto-detect", "auto-download").forEach { setting ->
                val property = "org.gradle.java.installations.$setting"
                providers
                    .systemProperty(property)
                    .orElse(providers.gradleProperty(property))
                    .orNull
                    ?.let { args("-D$property=$it") }
            }
        }
    }

smokeTasks[1].configure { mustRunAfter(smokeTasks[0]) }

tasks.register("publicationSmokeTest") {
    group = "verification"
    description =
        "Verifies publication metadata and independent consumers through Gradle and Maven POMs."
    dependsOn(smokeTasks)
}
