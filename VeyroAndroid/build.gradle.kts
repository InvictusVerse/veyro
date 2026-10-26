// Veyro Android — root build file.
//
// Nothing is applied here. The only job of this block is to put the right plugin versions on the
// build classpath for the modules (and the convention plugins) to apply.
//
// AGP 9 note, and this is load-bearing: AGP 9 compiles Kotlin itself ("built-in Kotlin"), and it
// bundles its own, older Kotlin Gradle Plugin. Declaring the Kotlin plugins here with
// `apply false` forces the version catalog's KGP (2.4.20) onto the classpath instead. No module
// applies `org.jetbrains.kotlin.android` — AGP does that work.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
