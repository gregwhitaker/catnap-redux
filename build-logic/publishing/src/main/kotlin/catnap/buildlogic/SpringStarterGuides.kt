package catnap.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named

// Keep the examples readable in the checkout and stamp only Catnap's BOM version in archives.
private val bomVersion =
    Regex("""(com\.github\.gregwhitaker:catnap-bom:|<catnap\.version>)[0-9][0-9A-Za-z.-]*""")

fun Project.bundleSpringStarterGuides() {
    val publicationVersion = version.toString()

    fun Jar.includeGuides(vararg paths: Any) {
        inputs.property("guideVersion", publicationVersion)
        from(paths.toList()) {
            filteringCharset = "UTF-8"
            filesMatching(listOf("*.md", "*.html")) {
                filter { line: String ->
                    bomVersion.replace(line) { match -> match.groupValues[1] + publicationVersion }
                }
            }
        }
    }

    tasks.named<Jar>("sourcesJar") { includeGuides("README.md") }
    tasks.named<Jar>("javadocJar") {
        includeGuides(
            "publication-docs",
            rootProject.file("website/content/INSTALLATION.md"),
            rootProject.file("website/content/SPRING_INTEGRATION.md"),
        )
    }
}
