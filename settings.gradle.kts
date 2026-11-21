pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // app.nolva artifacts come only from nolva-core's GitHub Packages.
        // Credentials: gpr.user / gpr.token in ~/.gradle/gradle.properties
        // locally (token with read:packages); GITHUB_ACTOR / GITHUB_TOKEN on CI.
        exclusiveContent {
            forRepository {
                maven {
                    name = "nolvaCore"
                    url = uri("https://maven.pkg.github.com/hpaez/nolva-core")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.token")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
            filter { includeGroup("app.nolva") }
        }
    }
}

rootProject.name = "nolva-android"
include(":app")
