plugins { `kotlin-dsl` }

dependencyLocking { lockAllConfigurations() }

tasks.register("resolveDependencies") {
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
