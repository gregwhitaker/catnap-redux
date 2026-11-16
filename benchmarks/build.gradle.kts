plugins {
    id("catnap.jvm-conventions")
    id("catnap.testing")
}

dependencies {
    implementation(project(":catnap-core"))
    implementation(project(":catnap-jackson3"))
    implementation(project(":catnap-http"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
}

val benchmarkJava = providers.gradleProperty("benchmarkJava").map(String::toInt).orElse(25)

val benchmarkEnvironment =
    tasks.register<JavaExec>("benchmarkEnvironment") {
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("com.github.gregwhitaker.catnap.benchmarks.EnvironmentReport")
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(benchmarkJava.map(JavaLanguageVersion::of))
            }
        )
        args(
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.file("results/environment.json").get().asFile.absolutePath,
        )
    }

fun JavaExec.configureJmh(smoke: Boolean) {
    dependsOn(tasks.named("test"), benchmarkEnvironment)
    group = "benchmark"
    description =
        if (smoke) "Checks every benchmark setup without collecting release measurements."
        else "Measures query and JSON pipeline costs and allocation with forked JMH JVMs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(benchmarkJava.map(JavaLanguageVersion::of))
        }
    )
    val result =
        layout.buildDirectory.file(if (smoke) "results/jmh-smoke.json" else "results/jmh.json")
    doFirst { result.get().asFile.parentFile.mkdirs() }
    args(
        providers
            .gradleProperty("jmhInclude")
            .getOrElse("com.github.gregwhitaker.catnap.benchmarks.*Benchmark.*"),
        "-f",
        if (smoke) "1" else "2",
        "-wi",
        if (smoke) "0" else "3",
        "-i",
        if (smoke) "1" else "5",
        "-w",
        "1s",
        "-r",
        if (smoke) "100ms" else "1s",
        "-t",
        "1",
        "-foe",
        "true",
        "-prof",
        "gc",
        "-jvmArgsAppend",
        "-Xms256m -Xmx512m",
        "-rf",
        "json",
        "-rff",
        result.get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("jmh") { configureJmh(false) }

tasks.register<JavaExec>("jmhSmoke") { configureJmh(true) }
