package com.learnde.app.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object SettingsMigration {
    suspend fun runIfNeeded(store: DataStore<AppSettings>) {
        val current = withTimeoutOrNull(3_000L) {
            store.data.catch { }.first()
        } ?: return

        var changed = false
        var next = current

        if (!current.model.contains("3.1")) {
            next = next.copy(model = "models/gemini-3.1-flash-live-preview")
            changed = true
        }
        if (current.latencyProfile == "UltraLow") {
            next = next.copy(latencyProfile = "Low")
            changed = true
        }

        if (changed) {
            runCatching {
                withTimeoutOrNull(3_000L) { store.updateData { next } }
            }
        }
    }
}