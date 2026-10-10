// Top-level build file — apply false so sub-projects opt in individually.
// (AGP 9 built-in Kotlin compiles app sources, same as the sibling apps;
//  the Compose compiler plugin is applied by :app.)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
