package com.veyro.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `veyro.android.application` — applied only by `:app`.
 *
 * Note that `org.jetbrains.kotlin.android` is deliberately NOT applied: under AGP 9 the Android
 * plugin compiles Kotlin itself. The root build file declares the Kotlin plugin with
 * `apply false` purely to pin the Kotlin Gradle Plugin version on the build classpath.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            configureAndroidApplication(this)
        }

        addLib("testImplementation", "junit4")
        addLib("testImplementation", "kotlinx-coroutines-test")
        addLib("androidTestImplementation", "androidx-test-junit")
        addLib("androidTestImplementation", "androidx-test-espresso-core")
    }
}
