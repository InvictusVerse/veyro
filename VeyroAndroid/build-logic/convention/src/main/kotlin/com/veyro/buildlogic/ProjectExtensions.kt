package com.veyro.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** The single version catalog, reachable from a convention plugin. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Kotlin compiler configuration, applied through the compile *tasks* rather than the `kotlin {}`
 * extension.
 *
 * Reason: under AGP 9 the Android plugin compiles Kotlin itself ("built-in Kotlin"), so which
 * extension type is registered on the project is an AGP implementation detail. The
 * [KotlinJvmCompile] task interface is stable public API either way, so configuring the tasks is
 * the version-robust hook. `kotlinOptions {}` does not exist at all under AGP 9.
 */
internal fun Project.configureKotlinCompiler() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(VeyroBuild.JVM_TARGET))
            // Treat all warnings seriously but do not fail the build on them yet; flip to
            // allWarningsAsErrors once the codebase is past the scaffolding stage.
            freeCompilerArgs.addAll(
                // Opt in to the experimental APIs the project genuinely relies on, instead of
                // sprinkling @OptIn across files.
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
}

/** Unit-test defaults shared by every module. */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed", "skipped")
            showExceptions = true
            showStackTraces = true
            showCauses = true
        }
    }
}

/** Adds a dependency from the catalog, e.g. `addLib("implementation", "androidx-core-ktx")`. */
internal fun Project.addLib(configuration: String, alias: String) {
    dependencies.add(configuration, libs.findLibrary(alias).get())
}

/** Adds a catalog bundle, e.g. `addBundle("implementation", "compose-ui")`. */
internal fun Project.addBundle(configuration: String, alias: String) {
    dependencies.add(configuration, libs.findBundle(alias).get())
}

/** Adds a project dependency, e.g. `addProject("implementation", ":core:model")`. */
internal fun Project.addProject(configuration: String, path: String) {
    dependencies.add(configuration, project(path))
}
