// J.A.R.V.I.S. — root build script
// Plugins are declared here (and applied per-module) so versions stay in one place.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
