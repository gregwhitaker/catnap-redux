dependencies { implementation("com.github.gregwhitaker:catnap-core") }

listOf(17, 21, 25).forEach { runtime ->
    tasks.register<Test>("testJava$runtime") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        javaLauncher =
            javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(runtime) }
    }
}
