plugins {
    id("catnap.kotlin-library")
    application
    id("catnap.benchmark-server")
}

dependencies {
    implementation(project(":benchmarks:streaming-fixture"))
    implementation(project(":catnap-ktor"))
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.negotiation)
    implementation(libs.ktor.kotlinx.json)
    implementation(libs.coroutines.jdk9)
}

application { mainClass = "com.github.gregwhitaker.catnap.benchmarks.ktor.ServerKt" }
