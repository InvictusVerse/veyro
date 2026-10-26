package com.veyro.buildlogic

import org.gradle.api.JavaVersion

/**
 * Build-wide constants.
 *
 * SDK levels are deliberately NOT in `libs.versions.toml`: the catalog is for dependency
 * coordinates, and putting plain integers there invites them to be referenced as if they were
 * artifact versions. See ProjectInfo/08-DECISIONS.md ADR-011 for the minSdk rationale.
 */
internal object VeyroBuild {

    /** API 37 / Android 17. Verified stable (non-preview) in the local SDK. */
    const val COMPILE_SDK = 37

    /** API 37 / Android 17. */
    const val TARGET_SDK = 37

    /** API 29 / Android 10 — ADR-011. */
    const val MIN_SDK = 29

    /**
     * Java 21 bytecode, matching the Gradle JVM (Android Studio JBR 21).
     * D8 still converts everything down to run on [MIN_SDK]. No toolchain block is used, so
     * Gradle never tries to auto-provision (download) a JDK.
     */
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_21

    const val JVM_TARGET = "21"

    const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
}
