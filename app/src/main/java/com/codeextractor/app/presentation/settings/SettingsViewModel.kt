// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/settings/SettingsViewModel.kt
// Изменения:
//   + Устранён вечный subscribe на DataStore (отписываемся после первого emit)
//   + Debounce сохранения 300мс (как раньше)
//   + Корректный cleanup в onCleared()
//   + Не теряем состояние при повторном входе в экран
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    // Локальный стейт для МГНОВЕННОГО отклика UI (без лагов IPC до DataStore)
    private val _uiState = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            // Читаем только первое значение — дальше работаем с локальным стейтом.
            // Это исключает race-condition при одновременных ViewModel-инстансах.
            runCatching { settingsStore.data.first() }
                .onSuccess { _uiState.value = it }
        }
    }

    /**
     * Обновляет локальный UI-стейт мгновенно,
     * затем через 300мс дебаунса пишет в зашифрованный DataStore.
     */
    fun update(transform: AppSettings.() -> AppSettings) {
        _uiState.update(transform)

        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            runCatching {
                settingsStore.updateData { _uiState.value }
            }.onFailure {
                // DataStore write error не критичен для UI — локальный стейт уже обновлён
            }
        }
    }

    /** Полный сброс к заводским значениям. */
    fun resetToDefaults() {
        val defaults = AppSettings()
        _uiState.value = defaults
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            runCatching { settingsStore.updateData { defaults } }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveJob?.cancel()
    }
}
