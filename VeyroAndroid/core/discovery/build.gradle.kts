plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.discovery"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    // Presence tokens are HMAC-derived from the pairing secret (04-PROTOCOL.md section 3.4).
    implementation(project(":core:crypto"))
    implementation(libs.androidx.core.ktx)
}