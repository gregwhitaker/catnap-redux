import catnap.buildlogic.bundleSpringStarterGuides

plugins { id("catnap.publishing") }

bundleSpringStarterGuides()

dependencies {
    api(project(":catnap-spring-boot-autoconfigure"))
    api(project(":catnap-spring-webflux"))
    api(platform(libs.spring.bom))
    api(libs.spring.webflux)
}
