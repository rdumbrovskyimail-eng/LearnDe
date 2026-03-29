package com.codeextractor.app

import android.app.Application
import com.codeextractor.app.util.AppLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GeminiLiveApplication : Application() {

    @Inject
    lateinit var appLogger: AppLogger

    override fun onCreate() {
        super.onCreate()
        appLogger.init()
        appLogger.d("=== APP STARTED (Gemini 3.1 Flash Live — MVI/Compose) ===")
    }
}