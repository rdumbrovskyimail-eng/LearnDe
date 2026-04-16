// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/codeextractor/app/data/settings/SettingsMigration.kt
//
// Одноразовая миграция для существующих пользователей.
// Гарантирует, что после обновления приложения:
//  - system instruction содержит блок про функции;
//  - model сброшен на 3.1 (если был 2.5);
//  - enableTestFunctions=true (если поля не было).
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first

object SettingsMigration {

    private const val FUNCTIONS_MARKER = "test_function_"

    suspend fun runIfNeeded(store: DataStore<AppSettings>) {
        val current = store.data.first()
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
            runCatching { store.updateData { next } }
        }
    }
}