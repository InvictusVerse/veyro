plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.media"
}

// ADR-066: the phone's screen capture, video encoder and playback audio. It captures and
// encodes only: no sockets, no peers. Output goes to a MediaSink (`:core:protocol`), which
// `:app` connects to the media socket in `:core:link`.
dependencies {
    api(project(":core:protocol"))
    implementation(project(":core:common"))
}
