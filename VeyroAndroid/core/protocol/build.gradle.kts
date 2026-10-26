plugins {
    alias(libs.plugins.veyro.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM: this module is the byte-for-byte reference the Windows client mirrors,
// and it must be unit-testable with no device (ADR-009, NFR-12, NFR-13).
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.core)
}