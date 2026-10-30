plugins {
    alias(libs.plugins.veyro.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // No kotlin-android plugin: AGP 9 compiles Kotlin itself (built-in Kotlin).
}

android {
    namespace = "com.veyro"

    defaultConfig {
        applicationId = "com.veyro"
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debug signing for now so `assembleRelease` is runnable locally. Replace with a
            // real signing config before any distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release-speed ART and Compose without release minification, for judging smoothness.
        create("profile") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".profile"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }
}

dependencies {
    // ── core ───────────────────────────────────────────────────────────────────────────────
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(project(":core:network"))
    implementation(project(":core:discovery"))
    implementation(project(":core:link"))
    implementation(project(":core:transfer"))
    implementation(project(":core:service"))
    implementation(project(":core:companion"))
    implementation(project(":core:media"))


    // ── android ────────────────────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle.compose)
    implementation(libs.kotlinx.coroutines.android)

    // ── compose ────────────────────────────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
