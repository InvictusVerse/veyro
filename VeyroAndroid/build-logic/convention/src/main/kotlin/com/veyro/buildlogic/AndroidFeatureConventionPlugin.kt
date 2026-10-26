package com.veyro.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `veyro.android.feature` - the baseline for every module under `feature/`.
 *
 * (Do not write a glob like "feature slash star" in a KDoc comment here: Kotlin supports nested
 * block comments, so the slash-star inside the doc opens a comment that never closes and the
 * file fails to parse.)
 *
 * A feature module is a Compose library plus the shared presentation dependencies, and nothing
 * else. Note what it does NOT give you: no transport, no discovery, no crypto. A feature talks to
 * repositories and managers that `:app` hands to its ViewModel, which is what keeps features
 * independent of each other (ProjectInfo/03-ARCHITECTURE.md, rule 2).
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("veyro.android.library.compose")

        addProject("implementation", ":core:model")
        addProject("implementation", ":core:common")
        addProject("implementation", ":core:designsystem")
        addProject("implementation", ":core:ui")

        addLib("implementation", "androidx-lifecycle-viewmodel-compose")
        addLib("implementation", "androidx-lifecycle-runtime-ktx")
        addLib("implementation", "androidx-navigation-compose")

        addProject("testImplementation", ":core:testing")
    }
}
