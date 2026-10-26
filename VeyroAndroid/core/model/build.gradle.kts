plugins {
    alias(libs.plugins.veyro.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM. An `android.*` import here is a defect (ADR-009, NFR-12).
dependencies {
    implementation(libs.kotlinx.serialization.core)
}