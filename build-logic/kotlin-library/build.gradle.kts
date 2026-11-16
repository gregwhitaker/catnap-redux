plugins { `kotlin-dsl` }

dependencies {
    implementation(project(":java-library"))
    implementation(libs.kotlin.gradle)
    implementation(libs.spotless.gradle)
    implementation(libs.dokka.gradle)
}

dependencyLocking { lockAllConfigurations() }

tasks.register("resolveDependencies") {
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
