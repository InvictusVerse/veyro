plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.veyro.core.testing"
}

// Fakes and fixtures shared by test source sets. Uses `api` so consumers inherit the test
// framework and the interfaces being faked without redeclaring them.
dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:data"))
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}