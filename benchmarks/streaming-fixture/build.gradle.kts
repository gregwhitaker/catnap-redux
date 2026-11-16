plugins {
    id("catnap.java-library")
    id("catnap.testing")
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
