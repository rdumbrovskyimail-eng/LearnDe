plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.codeextractor.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codeextractor.app"
        minSdk = 26          // Android 8.0 — AudioTrack.Builder, WebSocket стабильны
        targetSdk = 36       // Android 16 — edge-to-edge принудительный
        versionCode = 1
        versionName = "1.0"

        // API ключ из local.properties → BuildConfig.GEMINI_API_KEY
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${project.findProperty("GEMINI_API_KEY") ?: ""}\""
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)        // enableEdgeToEdge(), OnBackPressedDispatcher
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // WebSocket
    implementation(libs.okhttp)

    // JSON — сериализация сообщений Gemini Live API
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
}
