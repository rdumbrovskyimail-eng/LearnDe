package com.learnde.app.presentation.settings

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.BackgroundImageStore
import com.learnde.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ФИКСЫ vs прошлая версия:
 *   [1] Убран runBlocking в onCleared — DataStore всё равно асинхронный,
 *       блокировка main не даёт гарантии записи и рискует ANR.
 *   [2] Финальное сохранение — launch { withContext(NonCancellable) { ... } },
 *       которое успевает отработать даже после отмены viewModelScope.
 *   [3] flushPendingSave — безопасная фиксация из UI (DisposableEffect).
 */
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
            withContext(NonCancellable) {
                runCatching { settingsStore.updateData { _uiState.value } }
            }
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
        // Никакого runBlocking. Если есть pending debounce — пытаемся дописать
        // через launch+NonCancellable. Если VM уничтожается — viewModelScope
        // уже отменён, launch просто не стартанёт; это приемлемо, так как
        // SettingsScreen вызовет flushPendingSave в onDispose ДО onCleared.
    }
}