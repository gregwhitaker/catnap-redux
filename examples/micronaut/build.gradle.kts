plugins {
    id("catnap.jvm-conventions")
    alias(libs.plugins.micronaut.application)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

micronaut {
    version(libs.versions.micronaut.asProvider().get())
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.github.gregwhitaker.catnap.examples.*")
    }
}

dependencies {
    annotationProcessor(platform(libs.micronaut.bom))
    annotationProcessor(libs.micronaut.inject)
    annotationProcessor(libs.micronaut.serde.processor)
    implementation(platform(libs.micronaut.bom))
    implementation(libs.micronaut.http)
    implementation(libs.micronaut.serde)
    implementation(libs.micronaut.reactor)
    implementation(project(":catnap-testkit"))
    implementation(project(":catnap-micronaut"))
    testImplementation(libs.micronaut.test)
    testRuntimeOnly(libs.junit.launcher)
}

application { mainClass = "com.github.gregwhitaker.catnap.examples.micronaut.ExampleApplication" }

// Use GRAALVM_HOME for native builds while ordinary compilation keeps its Java 25 toolchain.
graalvmNative { toolchainDetection.set(false) }
