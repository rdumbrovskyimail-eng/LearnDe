// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/codeextractor/app/presentation/settings/SettingsViewModel.kt
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.settings

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeextractor.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.data
        .catch { emit(AppSettings()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    fun update(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            settingsStore.updateData { current -> current.transform() }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsStore.updateData { AppSettings() }
        }
    }
}