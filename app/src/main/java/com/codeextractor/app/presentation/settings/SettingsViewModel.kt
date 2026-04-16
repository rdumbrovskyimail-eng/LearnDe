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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    // Локальный стейт для МГНОВЕННОГО отклика UI (без лагов)
    private val _uiState = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _uiState.asStateFlow()

    private var initDone = false
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.data.collect { stored ->
                if (!initDone) {
                    _uiState.value = stored
                    initDone = true
                }
            }
        }
    }

    fun update(transform: AppSettings.() -> AppSettings) {
        // 1. Мгновенно обновляем UI
        _uiState.update(transform)
        
        // 2. Отменяем предыдущую задачу сохранения, если юзер все еще тянет ползунок
        saveJob?.cancel()
        
        // 3. Сохраняем в файл только через 300мс после того, как юзер отпустил палец
        saveJob = viewModelScope.launch {
            delay(300)
            settingsStore.updateData { _uiState.value }
        }
    }

    fun resetToDefaults() {
        val defaults = AppSettings()
        _uiState.value = defaults
        saveJob?.cancel()
        viewModelScope.launch {
            settingsStore.updateData { defaults }
        }
    }
}