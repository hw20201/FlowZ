// Top-level build file. AGP 9.1.0 / Kotlin 2.2.21 / Compose BOM 2025.10.01,
// the combination proven by the Radial build (AGP 9 has built-in Kotlin:
// do NOT apply org.jetbrains.kotlin.android, do NOT use kotlinOptions).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
