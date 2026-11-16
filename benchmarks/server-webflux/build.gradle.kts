plugins {
    id("catnap.jvm-conventions")
    application
    id("catnap.benchmark-server")
}

dependencies {
    implementation(project(":benchmarks:streaming-fixture"))
    implementation(project(":catnap-spring-boot-starter-webflux"))
}

application { mainClass = "com.github.gregwhitaker.catnap.benchmarks.webflux.Server" }
