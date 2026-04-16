package com.learnde.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.datastore.core.DataStore
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.presentation.navigation.AppNavGraph
import com.learnde.app.presentation.theme.GeminiLiveTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Точка входа. Читаем настройки РЕАКТИВНО (collectAsState), без
 * stateIn(Eagerly, ...) — чтобы не блокировать main-поток на холодном
 * старте расшифровкой AES-GCM.
 *
 * Тема обновляется автоматически при изменении settings.themeMode
 * из SettingsScreen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: DataStore<AppSettings>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by settingsStore.data.collectAsState(initial = AppSettings())
            GeminiLiveTheme(themeMode = settings.themeMode) {
                AppNavGraph()
            }
        }
    }
}