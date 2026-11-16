plugins { `kotlin-dsl` }

dependencies { implementation(libs.spotless.gradle) }

dependencyLocking { lockAllConfigurations() }

tasks.register("resolveDependencies") {
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
