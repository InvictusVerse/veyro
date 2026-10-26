plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.service"
}

// Foreground services and notifications. Depends on :core:model and :core:common only - the
// services are told what to display, they do not reach down into transport or transfer themselves.
dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    // The background service collects its host's status Flow (ADR-036). Already on the classpath.
    implementation(libs.kotlinx.coroutines.android)
}
