plugins {
    alias(libs.plugins.veyro.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.veyro.core.link"
}

/*
 * The link layer: WebSocket transport, the preamble handshake, PIN pairing and the authenticated
 * session.
 *
 * Ported from the author's Invox ecosystem code (`services/ecosystem/`), which is a working
 * system on these devices. Veyro's previous raw-TCP transport compiled and passed loopback tests
 * but did not connect between two real phones; this stack does.
 */
dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:protocol"))
    // PairConfirm: commitment, code and bootstrap-key derivations for confirm pairing (ADR-031).
    // The X25519 step itself stays behind IdentityManager, supplied by :app as a lambda.
    implementation(project(":core:crypto"))
    // The transport-agnostic file logic: PartFile (.veyropart lifecycle, resume) and ChunkMath
    // (boundaries, hashing). LinkTransferRunner reuses these over a LinkSession (ADR-020).
    implementation(project(":core:network"))
    implementation(project(":core:transfer"))

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
}