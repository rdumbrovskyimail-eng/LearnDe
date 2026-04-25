// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (заглушка для A2/B1/B2)
// Путь: app/src/main/java/com/learnde/app/learn/data/CefrDataImporter.kt
//
// Унифицированный импортёр данных для любого уровня CEFR.
// Сейчас A1 импортируется через A1DataImporter (legacy).
// При появлении A2/B1/B2 — мигрируем сюда.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data

import android.content.Context
import com.learnde.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CefrDataImporter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val logger: AppLogger,
) {
    /**
     * Будущий импортёр для A2/B1/B2.
     * Структура assets аналогична a1: clean_X_lemmas.json + X_clusters.json.
     */
    suspend fun importLevelIfNeeded(level: CefrLevel) {
        // TODO: Реализовать когда будут готовы JSON для A2-B2.
        logger.d("CefrImporter: import for ${level.code} not yet implemented")
    }
    
    fun isAssetAvailable(level: CefrLevel): Boolean {
        return try {
            ctx.assets.open("${level.assetsFolder}/clean_${level.code.lowercase()}_lemmas.json").close()
            true
        } catch (_: Exception) {
            false
        }
    }
}