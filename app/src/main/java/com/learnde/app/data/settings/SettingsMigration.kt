package com.learnde.app.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object SettingsMigration {

    suspend fun runIfNeeded(store: DataStore<AppSettings>) {
        // Таймаут 3 секунды — если не успели прочитать, пропускаем миграцию
        val current = withTimeoutOrNull(3_000L) {
            store.data.catch { /* игнорируем ошибки чтения */ }.first()
        } ?: return

        var changed = false
        var next = current

        // 2) Модель: мигрировать 2.5 → 3.1
        if (!current.model.contains("3.1")) {
            next = next.copy(model = "models/gemini-3.1-flash-live-preview")
            changed = true
        }

        if (current.latencyProfile == "UltraLow") {
            next = next.copy(latencyProfile = "Low"); changed = true
        }

        if (current.tutorModel.startsWith("gemini-2.5")) {
            next = next.copy(tutorModel = "gemini-3.1-flash-lite"); changed = true
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