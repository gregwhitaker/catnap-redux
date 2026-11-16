plugins {
    `java-platform`
    id("catnap.publishing")
}

// Only Catnap modules are aligned. Framework versions stay under the application's control.
dependencies {
    constraints {
        rootProject
            .file("gradle/published-modules.txt")
            .readLines()
            .filter { it.isNotBlank() }
            .forEach { api("${project.group}:$it:${project.version}") }
    }
}
