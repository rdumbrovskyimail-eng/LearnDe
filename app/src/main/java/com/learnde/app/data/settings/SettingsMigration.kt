package com.learnde.app.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object SettingsMigration {

    private const val FUNCTIONS_MARKER = "test_function_"

    suspend fun runIfNeeded(store: DataStore<AppSettings>) {
        // Таймаут 3 секунды — если не успели прочитать, пропускаем миграцию
        val current = withTimeoutOrNull(3_000L) {
            store.data.catch { /* игнорируем ошибки чтения */ }.first()
        } ?: return

        var changed = false
        var next = current

        // 1) System instruction: добавить блок функций, если отсутствует
        if (!current.systemInstruction.contains(FUNCTIONS_MARKER)) {
            next = next.copy(systemInstruction = AppSettings.DEFAULT_SYSTEM_INSTRUCTION)
            changed = true
        }

        // 2) Модель: мигрировать 2.5 → 3.1
        if (!current.model.contains("3.1")) {
            next = next.copy(model = "models/gemini-3.1-flash-live-preview")
            changed = true
        }

        // 3) Режим сцены: если передано неизвестное значение — сбросить на avatar
        val validModes = setOf("avatar", "visualizer", "custom_image")
        if (current.sceneMode !in validModes) {
            next = next.copy(sceneMode = "avatar")
            changed = true
        }

        if (changed) {
            val finalNext = next
            runCatching {
                withTimeoutOrNull(3_000L) {
                    store.updateData { finalNext }
                }
            }
        }
    }
}