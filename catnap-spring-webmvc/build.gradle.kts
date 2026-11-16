plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

val verifyAdapterDependencies =
    tasks.register("verifyAdapterDependencies") {
        group = "verification"
        doLast {
            val modules =
                configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            check(
                modules.none {
                    it.moduleVersion.id.name == "spring-webflux" ||
                        it.moduleVersion.id.group.startsWith("io.projectreactor")
                }
            ) {
                "The MVC adapter must not depend on WebFlux or Reactor"
            }
        }
    }

tasks.named("check") { dependsOn(verifyAdapterDependencies) }

dependencies {
    api(project(":catnap-http"))
    implementation(project(":catnap-jackson3"))
    implementation(project(":catnap-streams"))
    api(platform(libs.spring.bom))
    api(libs.spring.framework.webmvc)
    api(libs.reactive.streams)
    compileOnly(libs.servlet.api)
    testImplementation(libs.servlet.api)
    testImplementation(libs.spring.test)
}
