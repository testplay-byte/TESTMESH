// Top-level build file — apply false so sub-projects can opt-in individually.
// (AGP 9 built-in Kotlin compiles app sources; no separate kotlin plugin needed,
//  same as the sibling AIMESHVISION project.)
plugins {
    alias(libs.plugins.android.application) apply false
}
