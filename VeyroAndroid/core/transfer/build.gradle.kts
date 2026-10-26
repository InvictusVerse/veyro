plugins {
    alias(libs.plugins.veyro.android.library)
    // TransferManifest is persisted as the `.veyrometa` resume sidecar.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.veyro.core.transfer"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.core)
    // The trust store and resume sidecars use CBOR (ADR-006): they hold raw keys and chunk hashes.
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.okio)
}