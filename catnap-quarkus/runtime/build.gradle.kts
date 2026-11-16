plugins {
    id("catnap.publishing")
    id("catnap.testing")
    alias(libs.plugins.quarkus.extension)
}

quarkusExtension { deploymentModule.set("catnap-quarkus-deployment") }

dependencies {
    api(project(":catnap-http"))
    implementation(project(":catnap-jackson2"))
    implementation(project(":catnap-streams"))
    api(platform(libs.quarkus.bom))
    api(libs.quarkus.rest)
    testRuntimeOnly(libs.asm)
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
                        it.moduleVersion.id.name.endsWith("-deployment")
                }
            ) {
                "The Quarkus runtime must not depend on other adapters or augmentation modules"
            }
        }
    }

tasks.named("check") { dependsOn(verifyAdapterDependencies) }
