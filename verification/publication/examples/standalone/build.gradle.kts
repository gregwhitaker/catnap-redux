plugins { application }

dependencies {
    implementation("com.github.gregwhitaker:catnap-jackson3")
    implementation("com.github.gregwhitaker:catnap-streams")
}

application { mainClass = "com.github.gregwhitaker.catnap.examples.standalone.Standalone" }
