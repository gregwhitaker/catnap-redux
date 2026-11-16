import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("catnap.java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

// The pinned Kotlin plugin adds a second artifact to Java's sources variant and writes the same
// archive. Java's sourcesJar already includes Kotlin sources; retain one writer and one artifact.
val javaSourcesJar = tasks.named<Jar>("sourcesJar")

tasks.matching { it.name == "kotlinSourcesJar" }.configureEach { enabled = false }

afterEvaluate {
    configurations.named("sourcesElements") {
        outgoing.artifacts.clear()
        outgoing.artifact(javaSourcesJar)
    }
}

// Maven's documentation classifier may contain Dokka HTML; Java's javadoc task cannot read Kotlin.
tasks.named<Jar>("javadocJar") {
    from(
        tasks.named<DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml").flatMap {
            it.outputDirectory
        }
    )
}

dokka { dokkaPublications.configureEach { offlineMode.set(true) } }

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

val catalog = extensions.findByType<VersionCatalogsExtension>()?.find("libs")?.orElse(null)

if (catalog != null)
    spotless {
        kotlin {
            target("src/**/*.kt")
            ktfmt(catalog.findVersion("ktfmt").get().requiredVersion).kotlinlangStyle()
        }
    }
