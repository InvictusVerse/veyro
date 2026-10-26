plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.common"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}