plugins {
    id("catnap.kotlin-library")
    id("catnap.publishing")
    id("catnap.testing")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":catnap-core"))
    api(libs.kotlinx.json)
    testImplementation(project(":catnap-testkit"))
}
