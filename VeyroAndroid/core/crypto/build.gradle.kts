plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.crypto"
}

dependencies {
    api(project(":core:model"))
    // Vlp constants: the prologue and the KDF info strings are protocol, not crypto.
    api(project(":core:protocol"))
    implementation(project(":core:common"))

    // X25519 only, and only through the LIGHTWEIGHT API - never as a registered JCA provider, so
    // R8 can shrink it (ADR-016). Everything else (SHA-256, HMAC, ChaCha20-Poly1305) comes from
    // the platform.
    implementation(libs.bouncycastle.provider)
}

// No Robolectric here, deliberately. Robolectric provides no AndroidKeyStore provider, so it could
// not test the part that needs a device anyway. Injecting SealingKeyProvider instead made all the
// storage logic testable on a plain JVM, and left only key lifecycle for an instrumentation test.