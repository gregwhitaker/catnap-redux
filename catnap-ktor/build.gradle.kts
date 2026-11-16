plugins {
    id("catnap.kotlin-library")
    id("catnap.publishing")
    id("catnap.testing")
}

dependencies {
    api(project(":catnap-http"))
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    api(libs.ktor.server.sse)
    api(libs.kotlinx.json)
    implementation(project(":catnap-streams"))
    implementation(project(":catnap-kotlinx-json"))
    testImplementation(libs.ktor.test)
}

val verifyAdapterDependencies =
    tasks.register("verifyAdapterDependencies") {
        group = "verification"
        doLast {
            val modules =
                configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            check(
                modules.none {
                    it.moduleVersion.id.group.startsWith("org.springframework") ||
                        it.moduleVersion.id.group.startsWith("io.micronaut") ||
                        it.moduleVersion.id.group.startsWith("io.quarkus") ||
                        it.moduleVersion.id.name == "jackson-databind"
                }
            ) {
                "The Ktor adapter must not depend on other framework adapters or Jackson databind"
            }
        }
    }

tasks.named("check") { dependsOn(verifyAdapterDependencies) }
