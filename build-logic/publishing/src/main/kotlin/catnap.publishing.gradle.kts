import java.util.Locale

plugins {
    `maven-publish`
    signing
}

// The same publication metadata applies to the platform and ordinary libraries.
val platformPublication = plugins.hasPlugin("java-platform")

if (!platformPublication) apply(plugin = "catnap.java-library")

group = providers.gradleProperty("group").getOrElse("com.github.gregwhitaker")

version = providers.gradleProperty("version").getOrElse("unspecified")

val githubRepository =
    providers
        .gradleProperty("githubRepository")
        .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
        .orElse("gregwhitaker/catnap")
        .map { it.lowercase(Locale.ROOT) }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components[if (platformPublication) "javaPlatform" else "java"])
            pom {
                name = project.name
                description =
                    if (platformPublication) "Version alignment for Catnap artifacts"
                    else "Catnap JSON partial response library: ${project.name}"
                url = "https://github.com/gregwhitaker/catnap"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "gregwhitaker"
                        name = "Greg Whitaker"
                    }
                }
                scm {
                    url = "https://github.com/gregwhitaker/catnap"
                    connection = "scm:git:https://github.com/gregwhitaker/catnap.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "localVerification"
            url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
        }
        maven {
            name = "GitHubPackages"
            val repository = githubRepository.get()
            require(
                repository.matches(Regex("[a-z0-9-]+/[a-z0-9_.-]+")) &&
                    repository.substringAfter('/') !in setOf(".", "..")
            ) {
                "githubRepository must be a GitHub owner/repository, for example gregwhitaker/catnap"
            }
            url = uri("https://maven.pkg.github.com/$repository")
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
    }
}

// Signing is explicitly enabled for a release; normal development needs no credentials.
if (providers.gradleProperty("releaseSigning").orNull == "true") {
    signing { sign(publishing.publications) }
}
