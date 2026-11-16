pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "catnap-build-logic"

include(
    "jvm-conventions",
    "java-library",
    "kotlin-library",
    "testing",
    "publishing",
    "benchmark-server",
)
