import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val catnapVersion = providers.gradleProperty("catnapVersion").get()
val junitVersion = libs.versions.junit.get()

subprojects {
    if (path == ":examples") return@subprojects
    apply(plugin = "java-library")
    configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(if (name == "micronaut") 25 else 17))
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    dependencies {
        add("implementation", platform("com.github.gregwhitaker:catnap-bom:$catnapVersion"))
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        inputs.property("publicationMetadataMode", providers.gradleProperty("metadataMode"))
        useJUnitPlatform()
        maxHeapSize = "768m"
        maxParallelForks = 1
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    rootProject.tasks.named("assemble") { dependsOn(tasks.named("assemble")) }
    rootProject.tasks.named("check") { dependsOn(tasks.named("check")) }
}

tasks.register("compatibilityTest") { dependsOn(listOf(17, 21, 25).map { ":core:testJava$it" }) }

tasks.register("verifyPublishedDependencies") {
    doLast {
        val resolved = mutableSetOf<String>()
        subprojects
            .filter { it.path != ":examples" }
            .forEach { consumer ->
                val configuration = consumer.configurations.getByName("runtimeClasspath")
                val artifacts = configuration.incoming.artifacts.artifacts
                val modules =
                    artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                modules
                    .filter { it.group == "com.github.gregwhitaker" }
                    .forEach { module ->
                        check(module.version == catnapVersion) { "BOM failed to align $module" }
                        resolved.add(module.module)
                    }
                artifacts
                    .filter { it.id.componentIdentifier.displayName.contains("catnap-") }
                    .forEach {
                        check(
                            it.id.componentIdentifier is ModuleComponentIdentifier &&
                                it.file.extension == "jar"
                        ) {
                            "Catnap must be consumed as a published JAR: $it"
                        }
                    }
                val groups = modules.map { it.group }
                if (consumer.name !in listOf("micronaut", "quarkus")) {
                    check(
                        groups.none { it.startsWith("io.micronaut") || it.startsWith("io.quarkus") }
                    )
                }
                if (
                    consumer.name in
                        listOf("micronaut", "quarkus", "ktor", "core", "bindings", "standalone")
                ) {
                    check(groups.none { it.startsWith("org.springframework") })
                }
                if (consumer.name == "spring-boot-mvc") {
                    check(groups.none { it.startsWith("io.projectreactor") })
                    check(
                        modules.none {
                            it.module == "spring-webflux" || it.module == "catnap-spring-webflux"
                        }
                    )
                }
                if (consumer.name == "micronaut" || consumer.name == "ktor") {
                    check(modules.none { it.module == "jackson-databind" })
                }
                if (consumer.name == "quarkus")
                    check(modules.none { it.module.endsWith("-deployment") })
                if (consumer.name == "core")
                    check(modules.map { it.module } == listOf("catnap-core"))
            }
        val expected =
            file("gradle/published-modules.txt")
                .readLines()
                .filter { it.isNotBlank() && it != "catnap-quarkus-deployment" }
                .toSet()
        check(resolved.containsAll(expected)) { "Unconsumed artifacts: ${expected - resolved}" }
        // Quarkus resolves the deployment artifact during augmentation, never onto the app runtime.
        check(
            project(":examples:quarkus")
                .layout
                .buildDirectory
                .file("quarkus-app/quarkus-run.jar")
                .get()
                .asFile
                .isFile
        )
        println(
            "Verified independent artifact resolution, BOM alignment, and runtime dependency boundaries (${providers.gradleProperty("metadataMode").get()})."
        )
    }
    dependsOn(":examples:quarkus:quarkusBuild")
}

apply(from = "verify-metadata.gradle.kts")
