plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.negotiation)
    implementation(libs.ktor.kotlinx.json)
    implementation("com.github.gregwhitaker:catnap-ktor")
    implementation("com.github.gregwhitaker:catnap-testkit")
    testImplementation(libs.ktor.test)
}

application { mainClass = "com.github.gregwhitaker.catnap.examples.ktor.ExampleApplicationKt" }
