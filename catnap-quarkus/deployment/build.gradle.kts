plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

dependencies {
    implementation(project(":catnap-quarkus"))
    implementation(platform(libs.quarkus.bom))
    implementation(libs.quarkus.rest.deployment)
}
