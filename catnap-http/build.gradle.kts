plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

dependencies {
    api(project(":catnap-core"))
    testImplementation(project(":catnap-testkit"))
    testImplementation(libs.fixture.json)
}
