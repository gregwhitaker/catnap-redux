dependencies {
    api(platform(libs.spring.bom))
    api(libs.jackson3)
    api(
        platform(
            "com.github.gregwhitaker:catnap-bom:${providers.gradleProperty("catnapVersion").get()}"
        )
    )
    api("com.github.gregwhitaker:catnap-testkit")
}
