plugins {
    id("com.android.application")        version "9.1.0"   apply false  // ← 8.9.1 → 9.1.0
    id("org.jetbrains.kotlin.android")   version "2.3.20"  apply false  // ← 2.1.20 → 2.3.20
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose")        version "2.3.20" apply false
    id("com.google.devtools.ksp")        version "2.3.6"   apply false  // ← 2.1.20-1.0.32 → 2.3.6
    id("com.google.dagger.hilt.android") version "2.59.2"  apply false  // ← 2.57.1 → 2.59.2
    id("com.github.ben-manes.versions")  version "0.53.0"  apply false  // ← 0.51.0 → 0.53.0
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        val isUnstable = candidate.version
            .matches(".*(alpha|beta|rc|RC|m|M).*".toRegex())
        isUnstable
    }
}
5. app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
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
        buildConfig = true
        compose = true
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

    // AGP 9.x + Kotlin 2.3.x: kotlinOptions заменён на compilerOptions
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {

    // Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")             // ← 1.10.1 → 1.13.0
    implementation("com.google.android.material:material:1.13.0")       // ← 1.12.0 → 1.13.0

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01") // ← .00 → .01
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")      // ← 2.8.9 → 2.9.7

    // Hilt
    implementation("com.google.dagger:hilt-android:2.59.2")             // ← 2.57.1 → 2.59.2
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")       // ← 1.2.0 → 1.3.0

    // Network
    implementation("com.squareup.okhttp3:okhttp:5.3.2")                 // ← 4.12.0 → 5.3.2

    // Serialization & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0") // ← 1.8.1 → 1.10.0
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") // ← 1.10.1 → 1.10.2

    // DataStore
    implementation("androidx.datastore:datastore:1.2.1")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
}