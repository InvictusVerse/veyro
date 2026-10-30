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
// Phone Link style companion features over a paired session (ADR-025). Notifications first.
include(":core:companion")
// Screen capture, video encoder and playback audio (ADR-066). Captures and encodes, no sockets.
include(":core:media")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")

// No feature modules (ADR-046): every screen, companion features included, lives in `:core:ui`;
// every companion feature's logic lives in `:core:companion`. One pattern for every feature.
