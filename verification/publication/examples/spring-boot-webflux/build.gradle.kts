plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(platform(libs.spring.bom))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter-webflux")
    implementation("com.github.gregwhitaker:catnap-testkit")
    testImplementation(libs.spring.test)
    testImplementation("com.github.gregwhitaker:catnap-jackson3")
}
