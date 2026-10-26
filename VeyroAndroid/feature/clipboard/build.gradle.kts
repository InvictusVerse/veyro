plugins {
    alias(libs.plugins.veyro.android.feature)
}

// Placeholder for Veyro Clipboard (roadmap phase P6). No implementation yet.
// A feature module never depends on another feature module (ProjectInfo/03-ARCHITECTURE.md).
android {
    namespace = "com.veyro.feature.clipboard"
}
