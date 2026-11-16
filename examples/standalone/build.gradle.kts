plugins {
    id("catnap.jvm-conventions")
    id("catnap.testing")
    application
}

dependencies {
    implementation(project(":catnap-jackson3"))
    implementation(project(":catnap-streams"))
}

application { mainClass = "com.github.gregwhitaker.catnap.examples.standalone.Standalone" }
