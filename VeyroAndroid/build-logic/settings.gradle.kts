// build-logic is a separate, included build that produces the `veyro.*` convention plugins.
// It is not part of the Android build's project tree; VeyroAndroid/settings.gradle.kts pulls it
// in with `includeBuild("build-logic")`.

dependencyResolutionManagement {
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
    // Reuse the single source of truth for versions (AGENTS.md rule 5).
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
