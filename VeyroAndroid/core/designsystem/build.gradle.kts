plugins {
    alias(libs.plugins.veyro.android.library.compose)
}

android {
    namespace = "com.veyro.core.designsystem"
}

// Theme and primitive components only. This module must NOT know about domain types -
// that is `:core:ui`'s job (ProjectInfo/03-ARCHITECTURE.md, rule 3).
//
// Deliberately NOT here: androidx.compose.material:material-icons-extended. It declares every
// Material icon as a Kotlin function, which is tens of MB of dex in a debug build. The core icon
// set that ships with material3 covers what Veyro needs. If a specific extended icon is ever
// required, add that one icon as a vector drawable instead of pulling in the whole set.
dependencies {
    implementation(libs.androidx.core.ktx)
    // `api`, not `implementation`: features reference Icons.Filled.* directly.
    api(libs.androidx.compose.material.icons.core)
}