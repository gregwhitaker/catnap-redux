plugins { alias(libs.plugins.micronaut.application) }

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
    implementation("com.github.gregwhitaker:catnap-micronaut")
    implementation("com.github.gregwhitaker:catnap-testkit")
    testImplementation(libs.micronaut.test)
}

application { mainClass = "com.github.gregwhitaker.catnap.examples.micronaut.ExampleApplication" }
