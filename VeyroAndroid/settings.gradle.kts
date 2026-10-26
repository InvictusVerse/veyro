// Veyro Android — build settings.
//
// Notes for future edits (see ProjectInfo/09-ENVIRONMENT.md):
//  - Repositories are locked down with FAIL_ON_PROJECT_REPOS so no module can add its own.
//  - No foojay toolchain resolver on purpose: we build with the Gradle JVM (JDK 21 from
//    Android Studio's JBR) and never auto-provision a JDK, which would download one.
//  - build-logic is an included build providing the `veyro.*` convention plugins.

pluginManagement {
    includeBuild("build-logic")
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VeyroAndroid"

include(":app")

// ── core: portable, pure-JVM (no android.* allowed — ADR-009) ──────────────────────────────
include(":core:model")
include(":core:protocol")

// ── core: Android libraries ────────────────────────────────────────────────────────────────
include(":core:common")
include(":core:crypto")
include(":core:network")
include(":core:discovery")
// The link layer: WebSocket transport + PIN pairing, ported from the Invox ecosystem code.
// Supersedes :core:transport for device-to-device connections (ADR-020).
include(":core:link")
include(":core:transfer")
include(":core:data")
include(":core:service")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")

// ── feature modules ────────────────────────────────────────────────────────────────────────
//
// `feature/` is for genuine additions on top of the transfer core - things that are separable and
// optional, and could be built or dropped independently. Clipboard today; Sync and Handoff later.
//
// The core product's own screens (Home, Devices, Pairing, Send, Receive, History, Settings) live in
// `:core:ui` instead. Veyro *is* a file-transfer app, so those are not features bolted onto it -
// they are the product, and seven modules for seven screens bought isolation nothing needed
// (ADR-019).
//
// A feature module still never depends on another feature module.
include(":feature:clipboard")
