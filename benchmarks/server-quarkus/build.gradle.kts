plugins {
    id("catnap.jvm-conventions")
    alias(libs.plugins.quarkus)
    id("catnap.benchmark-server")
}

dependencies {
    implementation(project(":benchmarks:streaming-fixture"))
    implementation(project(":catnap-quarkus"))
    implementation(enforcedPlatform(libs.quarkus.bom))
}
