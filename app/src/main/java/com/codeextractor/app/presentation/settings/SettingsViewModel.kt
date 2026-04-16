// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/settings/SettingsViewModel.kt
// Изменения:
//   + importSceneBackground / clearSceneBackground
//   + BackgroundImageStore инжектируется
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.settings

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeextractor.app.data.BackgroundImageStore
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
    private val settingsStore: DataStore<AppSettings>,
    private val bgStore: BackgroundImageStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { settingsStore.data.first() }
                .onSuccess { _uiState.value = it }
        }
    }

    fun update(transform: AppSettings.() -> AppSettings) {
        _uiState.update(transform)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            runCatching { settingsStore.updateData { _uiState.value } }
        }
    }

    /** Принудительный flush debounce'а — вызывать при уходе с экрана. */
    fun flushPendingSave() {
        saveJob?.cancel()
        viewModelScope.launch {
            runCatching { settingsStore.updateData { _uiState.value } }
        }
    }

    fun resetToDefaults() {
        val defaults = AppSettings()
        _uiState.value = defaults
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            runCatching { settingsStore.updateData { defaults } }
            runCatching { bgStore.clear() }
        }
    }

    /** Импорт PNG-фона. Результат сохраняется в internal-storage. */
    fun importSceneBackground(uri: Uri) {
        viewModelScope.launch {
            val ok = bgStore.importFromUri(uri)
            if (ok) update { copy(sceneBgHasImage = true) }
        }
    }

    fun clearSceneBackground() {
        viewModelScope.launch {
            bgStore.clear()
            update { copy(sceneBgHasImage = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush pending debounce перед уничтожением ViewModel
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            kotlinx.coroutines.runBlocking {
                runCatching { settingsStore.updateData { _uiState.value } }
            }
        }
    }
}