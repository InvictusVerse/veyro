plugins {
    alias(libs.plugins.veyro.android.feature)
}
// Veyro Clipboard screen (ADR-043). The clip logic lives in :core:companion (ClipboardBridge);
// :app hands it to ClipboardViewModel as plain flows and lambdas.
// A feature module never depends on another feature module (ProjectInfo/03-ARCHITECTURE.md).
android {
    namespace = "com.veyro.feature.clipboard"
}
