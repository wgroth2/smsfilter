plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.plugin)
}

// Release signing is supplied entirely through the environment so that no keystore
// path, password, or alias is ever committed. When the variables are absent (a normal
// developer machine), the release build falls back to the debug key so `assembleRelease`
// still succeeds locally; only CI/production builds produce a properly signed APK.
val releaseKeystorePath: String? = System.getenv("SMSFILTER_KEYSTORE_PATH")
val hasReleaseSigning: Boolean = !releaseKeystorePath.isNullOrBlank()

android {
    namespace = "com.digiroth.smsfilter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digiroth.smsfilter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("SMSFILTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SMSFILTER_KEY_ALIAS")
                keyPassword = System.getenv("SMSFILTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Enabled so debug-only logging can be gated on BuildConfig.DEBUG.
        buildConfig = true
    }

    // Fakes for the SMS pipeline are needed by both the JVM unit tests and the instrumented
    // worker test. AGP keeps `test` and `androidTest` source sets separate, so without this the
    // ~200 lines of recording fakes would have to be duplicated and would inevitably drift apart.
    // Additive only: no dependency, version, or existing setting is changed.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin is compiled by AGP's built-in Kotlin support (AGP 9+). The Kotlin JVM target
// defaults to `compileOptions.targetCompatibility` (17 above), so no explicit
// `kotlin { compilerOptions { ... } }` block is required.

// Every dependency in the pinned version catalog is declared here in Phase 1 rather than
// incrementally, so this file is written once and never edited by a later phase.
dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose (versions come from the BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle & ViewModel
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager (+ Hilt worker support)
    implementation(libs.workmanager.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // EncryptedSharedPreferences
    implementation(libs.security.crypto)

    // Retrofit + OkHttp
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Moshi
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.workmanager.testing)

    // Instrumented tests (device/emulator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.workmanager.testing)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}
