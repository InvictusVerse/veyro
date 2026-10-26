package com.veyro.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * `veyro.jvm.library` — for `:core:model` and `:core:protocol`.
 *
 * These are plain Kotlin/JVM modules on purpose (ADR-009): they are mirrored by the Windows
 * implementation and must stay free of `android.*` so the wire format can be unit-tested in
 * milliseconds without a device or emulator (NFR-12, NFR-13).
 *
 * If you ever find yourself wanting an Android API in one of these modules, the answer is to put
 * the Android-specific part in a different module, not to convert this one.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = VeyroBuild.JAVA_VERSION
            targetCompatibility = VeyroBuild.JAVA_VERSION
        }

        addLib("implementation", "kotlinx-coroutines-core")

        // JUnit 4 on purpose: it is what the Android modules use, so there is one test framework
        // across the whole build instead of two.
        addLib("testImplementation", "junit4")
        addLib("testImplementation", "kotlin-test-junit")
        addLib("testImplementation", "kotlinx-coroutines-test")

        configureKotlinCompiler()
        configureUnitTests()
    }
}
