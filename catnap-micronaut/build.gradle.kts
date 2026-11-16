plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    api(project(":catnap-http"))
    implementation(project(":catnap-streams"))
    api(platform(libs.micronaut.bom))
    api(libs.micronaut.server)
    api(libs.micronaut.json)
    api(libs.micronaut.reactor)
    implementation(libs.micronaut.jackson.core)
    annotationProcessor(platform(libs.micronaut.bom))
    annotationProcessor(libs.micronaut.inject)
    testImplementation(project(":catnap-testkit"))
    testImplementation(libs.micronaut.serde)
}

val verifyAdapterDependencies =
    tasks.register("verifyAdapterDependencies") {
        group = "verification"
        doLast {
            val modules =
                configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            check(
                modules.none {
                    it.moduleVersion.id.name == "jackson-databind" ||
                        it.moduleVersion.id.group.startsWith("org.springframework")
                }
            ) {
                "The Micronaut adapter must use native Micronaut trees without Jackson databind or Spring"
            }
        }
    }

tasks.named("check") { dependsOn(verifyAdapterDependencies) }
