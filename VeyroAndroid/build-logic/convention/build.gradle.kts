plugins {
    `kotlin-dsl`
}

group = "com.veyro.buildlogic"

// No `java { sourceCompatibility = ... }` block here on purpose. The `kotlin-dsl` plugin picks a
// JVM target that matches the Gradle runtime for both the Java and Kotlin compile tasks; setting
// only the Java side produces an "Inconsistent JVM-target compatibility" warning for no benefit.
// Convention-plugin bytecode only ever runs inside this Gradle daemon.

dependencies {
    // compileOnly: these plugins are applied by the consuming build, not shipped by us.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "veyro.android.application"
            implementationClass = "com.veyro.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "veyro.android.library"
            implementationClass = "com.veyro.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "veyro.android.library.compose"
            implementationClass = "com.veyro.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "veyro.android.feature"
            implementationClass = "com.veyro.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "veyro.jvm.library"
            implementationClass = "com.veyro.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
