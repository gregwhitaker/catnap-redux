plugins {
    id("catnap.jvm-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.bom))
    implementation(libs.spring.webflux)
    implementation(project(":catnap-spring-boot-starter-webflux"))
    implementation(project(":catnap-testkit"))
    testImplementation(libs.spring.test)
    testImplementation(project(":catnap-jackson3"))
    testRuntimeOnly(libs.junit.launcher)
}
