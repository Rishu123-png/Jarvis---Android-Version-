// J.A.R.V.I.S. — root build script
// Plugins are declared here (and applied per-module) so versions stay in one place.
plugins {
    // Both AGP plugin ids are resolved here, once. A module that asks for the library plugin while
    // the application plugin is already on the classpath fails to resolve its version ("already on
    // the classpath with an unknown version") — the whole artifact has to be named up front.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
