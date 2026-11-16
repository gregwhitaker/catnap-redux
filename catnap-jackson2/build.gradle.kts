plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

dependencies {
    api(project(":catnap-core"))
    api(libs.jackson2)
    testImplementation(project(":catnap-testkit"))
}
