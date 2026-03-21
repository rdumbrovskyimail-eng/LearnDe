plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.codeextractor.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codeextractor.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ── Существующие ────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── Lifecycle (обновлено 2.8.7 → 2.10.0) ───────────────────
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // ── Шифрование API-ключа ────────────────────────────────────
    // androidx.security:security-crypto:1.1.0 — все API deprecated.
    // 1.0.0 — последний стабильный без deprecated, работает корректно.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── DataStore + Tink ────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.crypto.tink:tink-android:1.14.0")

    // ── Timber ─────────────────────────────────────────────────
    implementation("com.jakewharton.timber:timber:5.0.1")
}