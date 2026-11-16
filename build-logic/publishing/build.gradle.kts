plugins { `kotlin-dsl` }

dependencies { implementation(project(":java-library")) }

dependencyLocking { lockAllConfigurations() }

tasks.register("resolveDependencies") {
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
