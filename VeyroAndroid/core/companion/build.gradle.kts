plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.companion"
}

// Companion features that ride the paired session (ADR-025): notification mirroring today.
// The mirror logic is pure Kotlin behind small interfaces so it is JVM-testable; only the
// NotificationListenerService and its adapter touch android.*.
dependencies {
    api(project(":core:link"))
    api(project(":core:model"))
    implementation(project(":core:common"))
}
