plugins { id("catnap.publishing") }

tasks.named<Jar>("sourcesJar") { from("README.md") }

tasks.named<Jar>("javadocJar") {
    from("publication-docs")
    from(rootProject.file("website/content/SPRING_INTEGRATION.md"))
}

dependencies {
    api(project(":catnap-spring-boot-autoconfigure"))
    api(project(":catnap-spring-webflux"))
    api(platform(libs.spring.bom))
    api(libs.spring.webflux)
}
