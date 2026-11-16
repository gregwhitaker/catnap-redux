plugins {
    id("catnap.jvm-conventions")
    id("catnap.testing")
}

dependencies { implementation(libs.jackson3) }

val frameworks =
    providers
        .gradleProperty("streamFrameworks")
        .getOrElse("mvc,webflux,micronaut,quarkus,ktor")
        .split(',')

require(
    frameworks.isNotEmpty() &&
        frameworks.distinct().size == frameworks.size &&
        frameworks.all { it in listOf("mvc", "webflux", "micronaut", "quarkus", "ktor") }
)

fun JavaExec.configureMeasurement(mode: String) {
    workingDir(rootProject.projectDir)
    group = "benchmark"
    description =
        if (mode == "smoke")
            "Checks native/projected streaming paths and measurement mechanics over HTTP."
        else if (mode == "stress")
            "Checks exact delivery across repeated HTTP pause/drain/resume boundaries."
        else
            "Measures native/projected NDJSON/SSE latency, throughput, allocation and retained memory."
    dependsOn(tasks.named("test"))
    dependsOn(frameworks.map { ":benchmarks:server-$it:benchmarkLaunchSpec" })
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.github.gregwhitaker.catnap.benchmarks.runner.StreamingMeasurements")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    maxHeapSize = "512m"
    if (mode == "stress") {
        listOf(
                "Seconds",
                "Boundaries",
                "Case",
                "Reconnect",
                "Synchronous",
                "ResumeFirst",
                "UnpausedStart",
                "Trace",
                "HoldReaders",
            )
            .forEach { name ->
                providers.gradleProperty("streamStress$name").orNull?.let {
                    systemProperty("catnap.stress.${name.lowercase()}", it)
                }
            }
    }
    args(mode, layout.buildDirectory.dir("results/$mode").get().asFile.absolutePath)
    frameworks.forEach {
        args(
            it,
            project(":benchmarks:server-$it")
                .layout
                .buildDirectory
                .file("benchmark-launch.txt")
                .get()
                .asFile
                .absolutePath,
        )
    }
}

tasks.register<JavaExec>("smoke") { configureMeasurement("smoke") }

tasks.register<JavaExec>("measure") { configureMeasurement("measure") }

tasks.register<JavaExec>("boundaryStress") { configureMeasurement("stress") }
