package com.veyro.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `veyro.android.library.compose` — adds Jetpack Compose to an Android library.
 *
 * Under Kotlin 2.x the Compose compiler is a Kotlin compiler plugin
 * (`org.jetbrains.kotlin.plugin.compose`) versioned together with Kotlin itself, so there is no
 * `composeOptions { kotlinCompilerExtensionVersion = ... }` block any more.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("veyro.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
        }

        val bom = libs.findLibrary("androidx-compose-bom").get()
        dependencies {
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
        }

        addBundle("implementation", "compose-ui")
        addLib("implementation", "androidx-lifecycle-runtime-compose")
        addLib("debugImplementation", "androidx-compose-ui-tooling")
        addLib("androidTestImplementation", "androidx-compose-ui-test-junit4")
        addLib("debugImplementation", "androidx-compose-ui-test-manifest")
    }
}
