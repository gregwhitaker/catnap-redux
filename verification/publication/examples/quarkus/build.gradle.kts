plugins { alias(libs.plugins.quarkus) }

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("com.github.gregwhitaker:catnap-quarkus")
    implementation("com.github.gregwhitaker:catnap-testkit")
    testImplementation(libs.quarkus.test)
    testImplementation("com.github.gregwhitaker:catnap-jackson2")
    testImplementation(libs.rest.assured)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
