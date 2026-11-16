import com.diffplug.gradle.spotless.SpotlessExtension
import java.io.DataInputStream

plugins {
    java
    id("com.diffplug.spotless")
}

group = providers.gradleProperty("group").getOrElse("com.github.gregwhitaker")

version = providers.gradleProperty("version").getOrElse("unspecified")

// Gradle's precompiled-plugin accessor extraction uses a synthetic project without this catalog.
val catalog = extensions.findByType<VersionCatalogsExtension>()?.find("libs")?.orElse(null)

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = java.toolchain.languageVersion.map { it.asInt() }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "768m"
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

if (catalog != null)
    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(catalog.findVersion("google-java-format").get().requiredVersion)
            removeUnusedImports()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktfmt(catalog.findVersion("ktfmt").get().requiredVersion).kotlinlangStyle()
        }
    }

dependencyLocking { lockAllConfigurations() }

tasks.register("resolveDependencies") {
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}

val verifyBytecode =
    tasks.register("verifyBytecode") {
        group = "verification"
        description =
            "Checks that production class files obey this module's declared Java baseline."
        dependsOn(tasks.named("classes"))
        val outputs = sourceSets.main.get().output.classesDirs
        inputs.files(outputs)
        val baseline = java.toolchain.languageVersion
        inputs.property("baseline", baseline.map { it.asInt() })
        doLast {
            outputs.asFileTree
                .matching { include("**/*.class") }
                .forEach { file ->
                    DataInputStream(file.inputStream()).use { data ->
                        check(data.readInt() == 0xCAFEBABE.toInt()) { "Invalid class file: $file" }
                        data.readUnsignedShort()
                        val major = data.readUnsignedShort()
                        check(major <= baseline.get().asInt() + 44) {
                            "$file has class version $major, above Java ${baseline.get()}"
                        }
                    }
                }
        }
    }

tasks.named("check") { dependsOn(verifyBytecode) }
