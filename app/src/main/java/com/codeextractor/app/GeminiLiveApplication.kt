package com.codeextractor.app

import android.app.Application
import androidx.datastore.core.DataStore
import com.codeextractor.app.data.settings.AppSettings
import com.codeextractor.app.data.settings.SettingsMigration
import com.codeextractor.app.util.AppLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GeminiLiveApplication : Application() {

    @Inject lateinit var appLogger: AppLogger
    @Inject lateinit var settingsStore: DataStore<AppSettings>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appLogger.init()
        appLogger.d("=== APP STARTED (Gemini 3.1 Flash Live — MVI/Compose) ===")

        // Одноразовая миграция старых настроек
        appScope.launch {
            SettingsMigration.runIfNeeded(settingsStore)
        }
    }
}