plugins { id("catnap.java-library") }

dependencies {
    api(platform(libs.spring.bom))
    api(libs.jackson3)
    api(project(":catnap-testkit"))
}
