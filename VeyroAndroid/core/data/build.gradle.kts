plugins {
    alias(libs.plugins.veyro.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.veyro.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(libs.androidx.core.ktx)

    // CBOR for the trust store: the records hold raw 32-byte keys, and JSON can only represent
    // those as integer arrays (~4x the bytes, slower to parse). JSON stays available for settings,
    // where the values are human-readable and being able to inspect the file is worth more.
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
}