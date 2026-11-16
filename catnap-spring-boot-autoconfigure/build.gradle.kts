plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

dependencies {
    api(project(":catnap-http"))
    api(platform(libs.spring.bom))
    api(libs.spring.autoconfigure)
    compileOnly(project(":catnap-spring-webmvc"))
    compileOnly(project(":catnap-spring-webflux"))
    compileOnly(libs.servlet.api)
    annotationProcessor(platform(libs.spring.bom))
    annotationProcessor(libs.spring.configuration.processor)
    testImplementation(libs.spring.test)
}
