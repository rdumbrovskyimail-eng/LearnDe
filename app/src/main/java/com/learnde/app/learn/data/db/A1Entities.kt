// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/data/db/A1Entities.kt
//
// Room entities для системы обучения A1.
// Главный принцип: каждая лемма и каждый кластер имеют свой
// прогресс, который обновляется в реальном времени через
// function calls от Gemini.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Лемма из Goethe A1 Wortschatz. Расширена в Patch 3 полями FSRS-5.
 */
@Entity(tableName = "a1_lemmas")
data class LemmaA1Entity(
    @PrimaryKey val uniqueId: String,
    val lemma: String,
    val pos: String,
    val article: String?,
    val articlesAll: String,
    val genus: String?,
    val urlDwds: String,
    val hidx: String?,

    // ─── Старые поля (оставлены для совместимости UI) ───
    val recognitionScore: Float = 0f,
    val productionScore: Float = 0f,
    val timesHeard: Int = 0,
    val timesProduced: Int = 0,
    val timesFailed: Int = 0,
    val lastSeenAt: Long? = null,
    val nextReviewAt: Long? = null,
    val lastClusterId: String? = null,

    // ─── Patch 3: FSRS-5 state ───
    val fsrsDifficulty: Double = 5.0,
    val fsrsStability: Double = 0.0,
    val fsrsReps: Int = 0,
    val fsrsLapses: Int = 0,
    val fsrsLastReviewAt: Long = 0L,
) {
    /** Индекс освоения. В Patch 3 считается через FsrsScheduler.masteryScore(). */
    val masteryScore: Float
        get() = (recognitionScore * 0.3f + productionScore * 0.7f).coerceIn(0f, 1f)

    fun isDueForReview(now: Long = System.currentTimeMillis()): Boolean =
        nextReviewAt?.let { it <= now } ?: true

    /** Построить FsrsState из полей. */
    fun toFsrsState(): com.learnde.app.learn.domain.FsrsState =
        com.learnde.app.learn.domain.FsrsState(
            difficulty = fsrsDifficulty,
            stability = fsrsStability,
            reps = fsrsReps,
            lapses = fsrsLapses,
            lastReviewAt = fsrsLastReviewAt,
        )
}

/**
 * Учебный кластер — группа 3-12 лемм с общей темой/грамматикой.
 * Источник: a1_clusters.json (~194 кластера покрывают 100% A1).
 *
 * Одна сессия = один кластер = 5-10 минут разговора с Gemini.
 */
@Entity(tableName = "a1_clusters")
data class ClusterA1Entity(
    /** Стабильный ID вида "c001_greeting_basic". */
    @PrimaryKey val id: String,

    /** Название на немецком ("Grüße und Verabschiedung"). */
    val titleDe: String,

    /** Название на русском для UI. */
    val titleRu: String,

    /** JSON-массив строк: леммы кластера. */
    val lemmasJson: String,

    /** Главная лемма (для иконки/короткого label). */
    val anchorLemma: String,

    /** v3.1: ПРЯМАЯ ссылка на правило из A1GrammarCatalog. */
    val grammarRuleId: String? = null,

    /** Грамматический фокус, который всплывает в ситуации. */
    val grammarFocus: String,

    /** Подсказка Gemini: какую ситуацию генерировать. */
    val scenarioHint: String,

    /** Категория (Zeit, Essen, Arbeit, ...) для UI-группировки. */
    val category: String,

    /** 1-4: порядок сложности. */
    val difficulty: Int,

    /** JSON-массив id кластеров-предшественников. */
    val prerequisitesJson: String,

    // ───── Прогресс кластера ─────

    /** Сколько раз ученик проходил этот кластер. */
    val attempts: Int = 0,

    /** 0..1. Индекс мастерства кластера (средний по леммам + оценка Gemini). */
    val masteryScore: Float = 0f,

    /** Когда кластер последний раз проходился. */
    val lastAttemptAt: Long? = null,

    /** Когда кластер будет предложен снова (SRS). null — не пройден. */
    val nextReviewAt: Long? = null,

    /** Считается ли кластер "разблокированным" (все prerequisites пройдены). */
    val isUnlocked: Boolean = false,
) {
    val isMastered: Boolean get() = masteryScore >= 0.7f
}

/**
 * Правило грамматики A1.
 * Правила НЕ изучаются отдельно. Они всплывают когда:
 *   - ученик услышал паттерн N раз (timesHeardInContext >= threshold)
 *   - в текущем кластере grammar_focus совпадает с этим правилом
 *
 * Тогда Gemini получает сигнал "пора показать" и одним
 * предложением на русском объясняет правило прямо в ситуации.
 */
@Entity(tableName = "a1_grammar_rules")
data class GrammarRuleA1Entity(
    @PrimaryKey val id: String,

    /** Название правила на немецком. */
    val nameDe: String,

    /** Название на русском для UI. */
    val nameRu: String,

    /** Краткое объяснение (1-2 предложения) для Gemini. */
    val shortExplanation: String,

    /** Несколько примеров для Gemini. */
    val examplesJson: String,

    /** Минимальное число экспозиций ДО первого объяснения. */
    val exposureThreshold: Int,

    /** Порядок сложности 1-5 — определяет порядок введения. */
    val difficulty: Int,

    // ───── Прогресс правила ─────

    /** Сколько раз ученик слышал паттерн в речи Gemini. */
    val timesHeardInContext: Int = 0,

    /** Было ли правило объяснено. */
    val wasIntroduced: Boolean = false,

    /** Когда впервые объяснено. */
    val introducedAt: Long? = null,

    /** Сколько раз ученик правильно применил правило. */
    val timesAppliedCorrectly: Int = 0,

    /** Сколько раз допустил ошибку в этом правиле. */
    val timesFailedOnThis: Int = 0,

    /** 0..1. Индекс освоения правила. */
    val masteryScore: Float = 0f,
)

/**
 * Лог одной сессии обучения. Расширен в Patch 2.5:
 *   - isComplete — дошла ли до finish_session
 *   - phaseReached — последняя достигнутая фаза
 *   - errorDiagnosesJson — агрегат диагнозов Selinker (map lemma→diagnosis)
 *   - avgQuality — средняя quality по всем evaluate вызовам
 *   - durationMs — длительность сессии (endedAt - startedAt)
 */
@Entity(tableName = "a1_session_logs")
data class A1SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clusterId: String,
    val startedAt: Long,
    val endedAt: Long,

    val lemmasTargetedJson: String,
    val lemmasProducedJson: String,
    val lemmasFailedJson: String,

    /** 1-7. Для incomplete-сессий рассчитан автоматически по производительности. */
    val overallQuality: Int,

    val feedbackText: String,
    val grammarRuleIntroduced: String?,

    // ─── Patch 2.5 fields ───

    /** true — Gemini вызвал finish_session. false — сессия прервана (Stop/crash). */
    val isComplete: Boolean = true,

    /** Последняя фаза на момент завершения (для incomplete). */
    val phaseReached: String = "COOL_DOWN",

    /**
     * JSON-мапа {lemma -> {source, depth, category, specifics}} —
     * агрегат всех диагнозов ошибок за сессию.
     * Для аналитики и подсказок в History-экране.
     */
    val errorDiagnosesJson: String = "{}",

    /** Средняя оценка качества по всем evaluate вызовам. 0f если вызовов не было. */
    val avgQuality: Float = 0f,

    /** Сколько было вызовов evaluate_and_update_lemma — индикатор активности. */
    val evaluateCallsCount: Int = 0,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
    val durationMinutes: Int get() = (durationMs / 60_000L).toInt()
}

/**
 * Хранилище общего прогресса A1.
 * Одна запись на пользователя.
 */
@Entity(tableName = "a1_user_progress")
data class A1UserProgressEntity(
    @PrimaryKey val userId: String = "default",
    val startedAt: Long = System.currentTimeMillis(),
    val lastSessionAt: Long? = null,
    val totalSessionsCompleted: Int = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,

    /** true если A1 считается пройденным (>= 90% кластеров с mastery >= 0.7). */
    val isA1Completed: Boolean = false,

    /** Дата прохождения A1. */
    val a1CompletedAt: Long? = null,
)
