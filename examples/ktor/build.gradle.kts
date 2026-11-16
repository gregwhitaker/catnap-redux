plugins {
    id("catnap.kotlin-library")
    id("catnap.testing")
    application
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.kotlinx.json)
    implementation(libs.kotlinx.json)
    implementation(project(":catnap-testkit"))
    implementation(project(":catnap-ktor"))
    testImplementation(libs.ktor.test)
}

application { mainClass = "com.github.gregwhitaker.catnap.examples.ktor.ProbeApplicationKt" }
