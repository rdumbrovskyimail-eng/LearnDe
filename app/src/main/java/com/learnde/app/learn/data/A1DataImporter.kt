// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/data/A1DataImporter.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data

import android.content.Context
import androidx.datastore.core.DataStore
import com.learnde.app.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1UserProgressDao
import com.learnde.app.learn.data.db.A1UserProgressEntity
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.learn.data.grammar.A1GrammarCatalog
import com.learnde.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    val unique_id: String? = null,
    val goethe_level: String = "A1",
)

@Serializable
private data class ClusterDto(
    val id: String,
    val title_de: String,
    val title_ru: String,
    val lemmas: List<String>,
    val anchor_lemma: String,
    val grammar_rule_id: String? = null,
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

    companion object {
        // КРИТИЧНО: при ЛЮБОМ изменении JSON-ассетов или миграциях БД, затрагивающих
        // импортируемые поля (например, MIGRATION_3_4 добавила grammarRuleId в кластеры),
        // эту константу ОБЯЗАТЕЛЬНО инкрементировать. Иначе у существующих юзеров
        // колонка останется пустой и кластеры не свяжутся с грамматикой.
        const val CURRENT_DATA_VERSION = 2
    }

    /**
     * Проверяет настройку и при необходимости импортирует всё.
     * Идемпотентно: повторный вызов безопасен.
     */
    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        val settings = settingsStore.data.first()
        val currentVersion = settings.a1DataVersion
        if (currentVersion >= CURRENT_DATA_VERSION) {
            logger.d("A1Importer: data version $currentVersion is current, skip")
            return@withContext
        }

        logger.d("A1Importer: starting import (from v$currentVersion to v$CURRENT_DATA_VERSION)...")
        val start = System.currentTimeMillis()

        try {
            importLemmas()
            importClusters()
            importGrammar()
            ensureUserProgress()

            settingsStore.updateData { 
                it.copy(a1DataImported = true, a1DataVersion = CURRENT_DATA_VERSION) 
            }

            val elapsed = System.currentTimeMillis() - start
            logger.d("A1Importer: DONE in ${elapsed}ms")
        } catch (e: Exception) {
            logger.e("A1Importer failed: ${e.message}", e)
            throw e
        }
    }

    // ────────── Леммы ──────────
    private suspend fun importLemmas() = withContext(Dispatchers.IO) {
        val raw = ctx.assets.open("a1/clean_a1_lemmas.json")
            .bufferedReader().use { it.readText() }
        val dtos = json.decodeFromString<List<LemmaDto>>(raw)

        // v3.1.2: дедупликация дублей (sein#1, sein#3 → sein#1).
        // Группируем по lemma, берём запись с минимальным hidx.
        val dedupedDtos = dtos
            .groupBy { it.lemma }
            .map { (_, group) ->
                group.minByOrNull { it.hidx?.toIntOrNull() ?: Int.MAX_VALUE }
                    ?: group.first()
            }

        val entities = dedupedDtos.map { dto ->
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
        // 1) INSERT IGNORE для новых лемм (прогресс существующих НЕ затирается).
        lemmaDao.insertAll(entities)
        // 2) UPDATE статических полей для уже существующих (например, если в JSON
        //    обновили перевод, артикль или part-of-speech). Прогресс остаётся.
        for (dto in dedupedDtos) {
            lemmaDao.updateStaticFields(
                lemma = dto.lemma,
                pos = dto.pos,
                article = dto.article,
                articlesAll = dto.articles_all.joinToString(","),
                genus = dto.genus,
                urlDwds = dto.url_dwds,
                hidx = dto.hidx,
            )
        }
        logger.d("A1Importer: synced ${entities.size} lemmas (deduped from ${dtos.size})")
    }

    // ────────── Кластеры ──────────
    private suspend fun importClusters() = withContext(Dispatchers.IO) {
        val raw = ctx.assets.open("a1/a1_clusters.json")
            .bufferedReader().use { it.readText() }
        val root = json.decodeFromString<ClustersRoot>(raw)

        val entities = root.clusters.map { dto ->
            ClusterA1Entity(
                id = dto.id,
                titleDe = dto.title_de,
                titleRu = dto.title_ru,
                lemmasJson = Json.encodeToString(dto.lemmas),
                anchorLemma = dto.anchor_lemma,
                grammarRuleId = dto.grammar_rule_id,
                grammarFocus = dto.grammar_focus,
                scenarioHint = dto.scenario_hint,
                category = dto.category,
                difficulty = dto.difficulty,
                prerequisitesJson = Json.encodeToString(dto.prerequisites),
                // Unlock правило: нет prerequisites → разблокирован сразу
                isUnlocked = dto.prerequisites.isEmpty(),
            )
        }
        clusterDao.insertAll(entities)
        entities.forEach { clusterDao.updateStaticFields(it) }

        val unlockedCount = entities.count { it.isUnlocked }
        logger.d("A1Importer: imported ${entities.size} clusters, $unlockedCount unlocked immediately")
    }

    // ────────── Грамматика ──────────
    private suspend fun importGrammar() = withContext(Dispatchers.IO) {
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