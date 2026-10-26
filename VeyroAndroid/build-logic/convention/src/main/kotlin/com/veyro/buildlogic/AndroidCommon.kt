package com.veyro.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project

/**
 * Shared `android {}` configuration.
 *
 * `ApplicationExtension` and `LibraryExtension` are configured by two near-identical functions
 * rather than one generic `CommonExtension<*, *, *, *, *, *>` function. The generic signature of
 * `CommonExtension` has changed between AGP major versions; the concrete DSL interfaces have not.
 * A few duplicated lines are a cheap price for not re-breaking the build on the next AGP bump.
 */
internal fun Project.configureAndroidApplication(extension: ApplicationExtension) = with(extension) {
    compileSdk = VeyroBuild.COMPILE_SDK

    defaultConfig {
        minSdk = VeyroBuild.MIN_SDK
        targetSdk = VeyroBuild.TARGET_SDK
        testInstrumentationRunner = VeyroBuild.TEST_RUNNER
        // Locale filtering (androidResources.localeFilters) is an APK-size measure and belongs
        // with the rest of the release work in roadmap P4, once there is a translation story.
    }

    compileOptions {
        sourceCompatibility = VeyroBuild.JAVA_VERSION
        targetCompatibility = VeyroBuild.JAVA_VERSION
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        // htmlReport/xmlReport are gone in AGP 9: lint reports are always generated, and the
        // artifacts are consumed through SingleArtifact.LINT_*_REPORT if a task needs them.
    }

    configureKotlinCompiler()
    configureUnitTests()
}

internal fun Project.configureAndroidLibrary(extension: LibraryExtension) = with(extension) {
    compileSdk = VeyroBuild.COMPILE_SDK

    defaultConfig {
        minSdk = VeyroBuild.MIN_SDK
        // No targetSdk here: AGP removed it from the library DSL. The application module's
        // targetSdk is what matters at runtime.
        testInstrumentationRunner = VeyroBuild.TEST_RUNNER
    }

    compileOptions {
        sourceCompatibility = VeyroBuild.JAVA_VERSION
        targetCompatibility = VeyroBuild.JAVA_VERSION
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        targetSdk = VeyroBuild.TARGET_SDK
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        targetSdk = VeyroBuild.TARGET_SDK
    }

    configureKotlinCompiler()
    configureUnitTests()
}
