buildscript {
    dependencies {
        // AGP 9 (built-in Kotlin) ships an older KGP runtime;
        // pin KGP to the Kotlin version in the catalog (AGP 9.0 release notes).
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
