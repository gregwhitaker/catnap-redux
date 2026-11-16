plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

dependencies {
    api(project(":catnap-http"))
    implementation(project(":catnap-jackson3"))
    implementation(project(":catnap-streams"))
    api(platform(libs.spring.bom))
    api(libs.spring.framework.webflux)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.webflux)
}

val verifyAdapterDependencies =
    tasks.register("verifyAdapterDependencies") {
        group = "verification"
        doLast {
            val modules =
                configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            check(
                modules.none {
                    it.moduleVersion.id.name == "spring-webmvc" ||
                        it.moduleVersion.id.group == "jakarta.servlet"
                }
            ) {
                "The WebFlux adapter must not depend on Spring MVC or the Servlet API"
            }
        }
    }

tasks.named("check") { dependsOn(verifyAdapterDependencies) }
