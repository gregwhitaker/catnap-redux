plugins { base }

val pluginProjects =
    listOf(
        "jvm-conventions",
        "java-library",
        "kotlin-library",
        "testing",
        "publishing",
        "benchmark-server",
    )

tasks.named("assemble") { dependsOn(pluginProjects.map { ":$it:assemble" }) }

tasks.named("check") { dependsOn(pluginProjects.map { ":$it:check" }) }

tasks.register("lockDependencies") {
    group = "build setup"
    description =
        "Resolves each convention plugin project's configurations; run with --write-locks."
    dependsOn(pluginProjects.map { ":$it:resolveDependencies" })
}
