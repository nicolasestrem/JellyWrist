import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing configuration identical to :app (DEC-012): the keystore is LOCAL
// (never committed) — `keystore.properties` at the root, gitignored. The *same* keystore as
// the watch is mandatory: the Wearable Data Layer only delivers between apps with the same
// `applicationId` AND the same signing certificate. In its absence (CI, developer build
// without a key), signingConfig stays null → unsigned APK, the build does not fail.
val releaseKeystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.example.jellywrist.mobile"
    compileSdk = 37

    defaultConfig {
        // Deliberately identical to the watch :app applicationId (DEC-012). The Wearable
        // Data Layer only delivers messages between apps with the SAME applicationId and
        // signing certificate on both devices — changing this breaks phone↔watch pairing.
        applicationId = "com.example.jellywatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            releaseKeystoreProperties.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
            keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
            storePassword = releaseKeystoreProperties.getProperty("storePassword")
            keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
                .takeIf { releaseKeystoreProperties.getProperty("storeFile") != null }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // `android.util.Log` calls in JVM-tested classes become no-ops instead of
        // "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
        )
    }
    lint {
        // Suppression rules in mobile/lint.xml — documented TLS (DEC-006) and exported
        // service (Data Layer) decisions.
        lintConfig = file("lint.xml")
        warningsAsErrors = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "Typos", "ObsoleteSdkInt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.turbine)
}