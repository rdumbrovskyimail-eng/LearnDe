package com.learnde.app

import android.app.Application
import androidx.datastore.core.DataStore
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.data.settings.SettingsMigration
import com.learnde.app.util.AppLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

        // Миграция настроек — с таймаутом, чтобы повреждённый Keystore
        // не повесил запуск приложения навсегда.
        appScope.launch {
            try {
                withTimeoutOrNull(5_000L) {
                    SettingsMigration.runIfNeeded(settingsStore)
                } ?: appLogger.w("SettingsMigration timeout — continuing with defaults")
            } catch (e: Exception) {
                appLogger.e("SettingsMigration crashed: ${e.message}", e)
                // Не крашим приложение — продолжаем с defaults.
            }
        }
    }
}