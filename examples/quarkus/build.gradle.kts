plugins {
    id("catnap.jvm-conventions")
    alias(libs.plugins.quarkus)
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.rest)
    implementation(project(":catnap-quarkus"))
    implementation(project(":catnap-testkit"))
    testImplementation(libs.quarkus.test)
    testImplementation(project(":catnap-jackson2"))
    testImplementation(libs.rest.assured)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

// Reuse the HTTP contracts against the built executable in the separate native task.
quarkus { sourceSets { setExtraNativeTest(sourceSets["integrationTest"]) } }
