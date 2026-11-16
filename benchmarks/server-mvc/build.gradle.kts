plugins {
    id("catnap.jvm-conventions")
    application
    id("catnap.benchmark-server")
}

dependencies {
    implementation(project(":benchmarks:streaming-fixture"))
    implementation(project(":catnap-spring-boot-starter"))
    implementation(platform(libs.spring.bom))
    // The native MVC baseline uses Spring's supported reactive return path.
    implementation(libs.reactor.core)
}

application { mainClass = "com.github.gregwhitaker.catnap.benchmarks.mvc.Server" }
