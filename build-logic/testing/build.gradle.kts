plugins { `kotlin-dsl` }

dependencies { implementation(project(":jvm-conventions")) }

dependencyLocking { lockAllConfigurations() }

tasks.register("resolveDependencies") {
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
