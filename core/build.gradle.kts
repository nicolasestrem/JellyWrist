import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.jellywrist.core"
    compileSdk = 37

    defaultConfig {
        // The lower of the two consuming apps: watch 34, phone 26.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // The session bootstrap and the token store now report which path they took, and
        // `android.util.Log` is a stub on the JVM: without this every unit test touching
        // either one dies on "not mocked". `:app` already carries the same setting for the
        // same reason.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Suppression rules in core/lint.xml — documented TLS decisions (DEC-006).
        lintConfig = file("lint.xml")
        warningsAsErrors = true
        // Informational version checks (network, non-deterministic): the catalog
        // is authoritative, not lint.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // `api`: SDK types (ApiClient, DTOs) leak through JellyfinBackend + the repositories,
    // and the @Serializable wire-protocol DTOs are part of :core's public surface.
    api(libs.jellyfin.core)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.security.crypto)
    // The SDK logs via kotlin-logging→slf4j; without a runtime backend it throws
    // NoClassDefFoundError on class load (docs/claude-code-reference.md). Required in production and JVM tests.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}