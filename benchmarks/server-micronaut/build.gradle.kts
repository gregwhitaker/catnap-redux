plugins {
    id("catnap.jvm-conventions")
    application
    id("catnap.benchmark-server")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

dependencies {
    implementation(project(":benchmarks:streaming-fixture"))
    implementation(project(":catnap-micronaut"))
    implementation(platform(libs.micronaut.bom))
    implementation(libs.micronaut.http)
    implementation(libs.micronaut.serde)
    runtimeOnly(libs.logback.classic)
    annotationProcessor(platform(libs.micronaut.bom))
    annotationProcessor(libs.micronaut.inject)
}

application { mainClass = "com.github.gregwhitaker.catnap.benchmarks.micronaut.Server" }
