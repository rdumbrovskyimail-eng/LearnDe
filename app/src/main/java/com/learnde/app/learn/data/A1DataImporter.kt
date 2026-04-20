// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/data/A1DataImporter.kt
//
// Импортёр справочных данных A1 в Room при первом запуске.
//
// Источники (положить файлы в assets/a1/):
//   - clean_a1_lemmas.json   → a1_lemmas (835 записей)
//   - a1_clusters.json        → a1_clusters (194 записи)
//   - A1GrammarCatalog.RULES  → a1_grammar_rules (22 записи, захардкожено)
//
// Импорт идёт один раз — факт импорта сохраняется в DataStore.
// Повторный импорт игнорируется (если только не сбросили флаг).
//
// ВАЖНО: импорт также проводит "разблокировку" первых кластеров
// (у которых нет prerequisites) — иначе система не запустится.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data

import android.content.Context
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1UserProgressDao
import com.learnde.app.learn.data.db.A1UserProgressEntity
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.learn.data.grammar.A1GrammarCatalog
import com.learnde.app.util.AppLogger
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// ─────────── DTO для парсинга JSON ───────────

@Serializable
private data class LemmaDto(
    val lemma: String,
    val pos: String,
    val article: String? = null,
    val articles_all: List<String> = emptyList(),
    val genus: String? = null,
    val url_dwds: String,
    val hidx: String? = null,
    val goethe_level: String = "A1",
)

@Serializable
private data class ClusterDto(
    val id: String,
    val title_de: String,
    val title_ru: String,
    val lemmas: List<String>,
    val anchor_lemma: String,
    val grammar_focus: String,
    val scenario_hint: String,
    val category: String,
    val difficulty: Int,
    val prerequisites: List<String> = emptyList(),
)

@Serializable
private data class ClustersRoot(
    val clusters: List<ClusterDto>,
)

// ─────────── Сам импортёр ───────────

@Singleton
class A1DataImporter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val lemmaDao: A1LemmaDao,
    private val clusterDao: A1ClusterDao,
    private val grammarDao: A1GrammarDao,
    private val progressDao: A1UserProgressDao,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Проверяет настройку и при необходимости импортирует всё.
     * Идемпотентно: повторный вызов безопасен.
     */
    suspend fun importIfNeeded() {
        val settings = settingsStore.data.first()
        if (settings.a1DataImported) {
            logger.d("A1Importer: already imported, skip")
            return
        }

        logger.d("A1Importer: starting import...")
        val start = System.currentTimeMillis()

        try {
            importLemmas()
            importClusters()
            importGrammar()
            ensureUserProgress()

            // Сохраняем флаг — больше не импортируем
            settingsStore.updateData { it.copy(a1DataImported = true) }

            val elapsed = System.currentTimeMillis() - start
            logger.d("A1Importer: DONE in ${elapsed}ms")
        } catch (e: Exception) {
            logger.e("A1Importer failed: ${e.message}", e)
            throw e
        }
    }

    // ────────── Леммы ──────────
    private suspend fun importLemmas() {
        val raw = ctx.assets.open("a1/clean_a1_lemmas.json")
            .bufferedReader().use { it.readText() }
        val dtos = json.decodeFromString<List<LemmaDto>>(raw)

        val entities = dtos.map { dto ->
            LemmaA1Entity(
                lemma = dto.lemma,
                pos = dto.pos,
                article = dto.article,
                articlesAll = dto.articles_all.joinToString(","),
                genus = dto.genus,
                urlDwds = dto.url_dwds,
                hidx = dto.hidx,
            )
        }
        lemmaDao.insertAll(entities)
        logger.d("A1Importer: imported ${entities.size} lemmas")
    }

    // ────────── Кластеры ──────────
    private suspend fun importClusters() {
        val raw = ctx.assets.open("a1/a1_clusters.json")
            .bufferedReader().use { it.readText() }
        val root = json.decodeFromString<ClustersRoot>(raw)

        val entities = root.clusters.map { dto ->
            ClusterA1Entity(
                id = dto.id,
                titleDe = dto.title_de,
                titleRu = dto.title_ru,
                lemmasJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        kotlinx.serialization.builtins.serializer<String>()
                    ),
                    dto.lemmas
                ),
                anchorLemma = dto.anchor_lemma,
                grammarFocus = dto.grammar_focus,
                scenarioHint = dto.scenario_hint,
                category = dto.category,
                difficulty = dto.difficulty,
                prerequisitesJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        kotlinx.serialization.builtins.serializer<String>()
                    ),
                    dto.prerequisites
                ),
                // Unlock правило: нет prerequisites → разблокирован сразу
                isUnlocked = dto.prerequisites.isEmpty(),
            )
        }
        clusterDao.insertAll(entities)

        val unlockedCount = entities.count { it.isUnlocked }
        logger.d("A1Importer: imported ${entities.size} clusters, $unlockedCount unlocked immediately")
    }

    // ────────── Грамматика ──────────
    private suspend fun importGrammar() {
        grammarDao.insertAll(A1GrammarCatalog.RULES)
        logger.d("A1Importer: imported ${A1GrammarCatalog.RULES.size} grammar rules")
    }

    // ────────── Юзер ──────────
    private suspend fun ensureUserProgress() {
        val existing = progressDao.get()
        if (existing == null) {
            progressDao.upsert(A1UserProgressEntity())
            logger.d("A1Importer: created user progress record")
        }
    }
}
