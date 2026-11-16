import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

pluginManager.withPlugin("java") {
    tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

    val launcher =
        extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    val quarkusServer = plugins.hasPlugin("io.quarkus")
    val launchFile = layout.buildDirectory.file("benchmark-launch.txt")
    val runtime = extensions.getByType<SourceSetContainer>().named("main").get().runtimeClasspath

    tasks.register("benchmarkLaunchSpec") {
        dependsOn(if (quarkusServer) "quarkusBuild" else "classes")
        inputs.files(runtime)
        inputs.property("java", launcher.map { it.executablePath.asFile.absolutePath })
        if (!quarkusServer)
            inputs.property("mainClass", project.extensions.getByType<JavaApplication>().mainClass)
        outputs.file(launchFile)
        doLast {
            val arguments = mutableListOf(launcher.get().executablePath.asFile.absolutePath)
            if (quarkusServer)
                arguments.addAll(
                    listOf(
                        "-jar",
                        layout.buildDirectory
                            .file("quarkus-app/quarkus-run.jar")
                            .get()
                            .asFile
                            .absolutePath,
                    )
                )
            else
                arguments.addAll(
                    listOf(
                        "-cp",
                        runtime.asPath,
                        project.extensions.getByType<JavaApplication>().mainClass.get(),
                    )
                )
            launchFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText(arguments.joinToString("\n") + "\n")
            }
        }
    }
}
