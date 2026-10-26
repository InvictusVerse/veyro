plugins {
    alias(libs.plugins.veyro.android.library.compose)
}

android {
    namespace = "com.veyro.ui"
}

/*
 * The app's UI layer: every screen of the core product plus the shared components they use.
 *
 * ## Why the main screens live here and not in `feature/`
 *
 * Veyro *is* a file-transfer app. Home, Devices, Pairing, Send, Receive, History and Settings are
 * not features bolted onto it - they are the product. Putting each in its own `:feature:*` module
 * bought module-level isolation that nothing needed and cost seven build files, seven namespaces and
 * seven places to look for a screen.
 *
 * `feature/` is now reserved for genuine additions on top of the transfer core - Clipboard today,
 * Sync and Handoff later. Those are separable, optional, and may be built or dropped
 * independently, which is exactly when a separate module earns its keep.
 *
 * ## This module sits ABOVE `:core:data`, not below it
 *
 * Note the layering change this implies, because it contradicts the original architecture note that
 * `:core:ui` was theme-and-primitives only. It now depends on `data`, `transfer`, `crypto` and
 * `network`, which makes it the presentation layer rather than a low-level shared module. The
 * `core/` prefix is now a slight misnomer; the alternative was a module rename churning every
 * import for no functional gain. Recorded in ADR-019.
 *
 * What has NOT changed: lower layers still never depend on higher ones. Nothing in `data`,
 * `transfer`, `transport`, `discovery`, `crypto`, `network`, `protocol` or `model` references this
 * module, and that is the rule that actually matters.
 */
dependencies {
    api(project(":core:designsystem"))
    api(project(":core:model"))
    implementation(project(":core:common"))

    // The presentation layer needs the stores and the coordinator the screens observe.
    implementation(project(":core:data"))
    implementation(project(":core:transfer"))
    // Pairing and device sessions (ADR-020). The pairing screen drives LinkManager directly.
    implementation(project(":core:link"))
    implementation(project(":core:protocol"))
    // Settings shows the identity fingerprint and owns the reset action.
    implementation(project(":core:crypto"))
    // Home shows the network banner and the named diagnosis.
    implementation(project(":core:network"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
}
