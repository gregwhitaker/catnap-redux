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
                    if (url.scheme == "https" && url.host == "maven.pkg.github.com") {
                        credentials {
                            username =
                                providers
                                    .gradleProperty("gpr.user")
                                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                                    .orNull
                            password =
                                providers
                                    .gradleProperty("gpr.key")
                                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                                    .orNull
                        }
                    }
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
