package com.veyro.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `veyro.android.library` — the baseline for every Android library module in `core/`.
 *
 * Gives every module: SDK levels, Java/Kotlin target, unit-test defaults, coroutines, and the
 * JUnit/Turbine test stack. It intentionally adds no UI dependencies; UI-bearing modules apply
 * `veyro.android.library.compose` on top.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidLibrary(this)
        }

        // Every module in this project is coroutine-based (ProjectInfo/03-ARCHITECTURE.md), and
        // coroutine types appear in public signatures (Flow, CoroutineDispatcher), so this has
        // to be `api` - with `implementation` consumers cannot even name the return types.
        addLib("api", "kotlinx-coroutines-core")
        addLib("implementation", "androidx-annotation")

        addLib("testImplementation", "junit4")
        addLib("testImplementation", "kotlinx-coroutines-test")
        addLib("testImplementation", "turbine")
        // For JVM tests that need a real Android surface (ApplicationProvider, ContentResolver).
        // Paired with Robolectric in the modules that actually use it.
        addLib("testImplementation", "androidx-test-core")
    }
}
