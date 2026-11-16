pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

val verificationRepository = providers.gradleProperty("catnapRepository").get()
val metadataMode = providers.gradleProperty("metadataMode").get()

require(metadataMode in listOf("gradle", "pom"))

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "catnapVerification"
                    url = uri(verificationRepository)
                    metadataSources {
                        if (metadataMode == "gradle") gradleMetadata()
                        else {
                            mavenPom()
                            ignoreGradleMetadataRedirection()
                        }
                    }
                }
            }
            filter { includeGroup("com.github.gregwhitaker") }
        }
        mavenCentral()
    }
}

// Deliberately no composite build, source substitution, or reference to Catnap project outputs.
rootProject.name = "catnap-published-consumers"

include(
    "core",
    "bindings",
    "examples:standalone",
    "examples:jackson3-probe",
    "examples:spring-boot-mvc",
    "examples:spring-boot-webflux",
    "examples:micronaut",
    "examples:quarkus",
    "examples:ktor",
)
