package com.codeextractor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.datastore.core.DataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.data.settings.AppSettings
import com.codeextractor.app.data.settings.ThemeMode
import com.codeextractor.app.presentation.navigation.AppNavGraph
import com.codeextractor.app.presentation.theme.GeminiLiveTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: DataStore<AppSettings>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeFlow = settingsStore.data
            .stateIn(lifecycleScope, SharingStarted.Eagerly, AppSettings())

        setContent {
            val settings by themeFlow.collectAsStateWithLifecycle()
            GeminiLiveTheme(themeMode = settings.themeMode) {
                AppNavGraph()
            }
        }
    }
}