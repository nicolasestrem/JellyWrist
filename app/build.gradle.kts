import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
// Release signing configuration (release build). The keystore is LOCAL (never committed):
// `keystore.properties` at the project root, gitignored. In its absence (CI, developer build
// without a key), signingConfig stays null → unsigned APK, the build does not fail.
val releaseKeystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.example.jellywrist"
    compileSdk = 37

    defaultConfig {
        // The published package ID is deliberately retained. Changing it would make an
        // upgrade install as a separate app and strand existing downloads, preferences,
        // and encrypted credentials under the legacy JellyWatch identity.
        applicationId = "com.example.jellywatch"
        minSdk = 34
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
            // R8 enabled (PLAN.md §8 task 14): the rules in proguard-rules.pro cover
            // the Jellyfin SDK's generated serializers; Hilt/Room/Media3 ship their
            // consumer rules in their AARs.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only if `keystore.properties` is present (local); otherwise unsigned
            // APK — CI has no key and must be able to build.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { releaseKeystoreProperties.getProperty("storeFile") != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // `android.util.Log` calls in JVM-tested classes (CompanionCommandHandler, appScope
        // exception handler) become no-ops instead of "not mocked" crashes.
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
        warningsAsErrors = true
        // `androidx.media3.common.util.UnstableApi` relies on `androidx.annotation.RequiresOptIn`,
        // which the Kotlin compiler does not recognize as an opt-in marker — only Lint flags
        // it. The entire download and playback engine depends on these APIs
        // (DownloadManager, SimpleCache, CacheDataSource); the opt-in is assumed and the
        // Media3 version is pinned in the catalog, so annotating each file individually
        // would add no information.
        // Informational version checks (GradleDependency, NewerVersionAvailable,
        // AndroidGradlePluginVersion): they query the network and flag any non-latest version
        // — non-deterministic noise for a CI gate. Updates are made deliberately in the
        // version catalog, not via lint.
        disable += setOf("UnsafeOptInUsageError", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    sourceSets {
        // Room's exported JSON schemas serve as fixtures for migration tests
        // (MigrationTestHelper reads the v1 schema to create the original database) — REQ-012.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Room exports its JSON schema: required to write and test migrations
// (REQ-012 / DEC-009 — no destructive migration in release).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // The entire Horologist surface used (audio, media3, network-awareness) is marked
        // @ExperimentalHorologistApi. The opt-in is assumed: version pinned in the catalog,
        // annotating each call would add no information.
        optIn.add("com.google.android.horologist.annotations.ExperimentalHorologistApi")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    implementation(libs.androidx.core.ktx)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // No `media3-datasource-okhttp` by design: the AAR is compiled against OkHttp 4
    // while the Jellyfin SDK requires OkHttp 5 at runtime (incompatible `Response.body`
    // signatures); `data/net/JellyDataSource` carries the adaptation, which also allows
    // scoping TLS trust to the chosen server (DEC-006).
    implementation(libs.media3.exoplayer.workmanager)

    implementation(libs.horologist.media3.backend)
    implementation(libs.horologist.media3.outputswitcher)
    implementation(libs.horologist.audio)
    implementation(libs.horologist.network.awareness)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.ongoing)

    testImplementation(libs.turbine)

    androidTestImplementation(libs.room.testing)
}
