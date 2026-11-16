plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation("com.github.gregwhitaker:catnap-jackson2")
    implementation("com.github.gregwhitaker:catnap-jackson3")
    implementation("com.github.gregwhitaker:catnap-kotlinx-json")
    implementation("com.github.gregwhitaker:catnap-http")
    implementation("com.github.gregwhitaker:catnap-streams")
    implementation("com.github.gregwhitaker:catnap-testkit")
}
