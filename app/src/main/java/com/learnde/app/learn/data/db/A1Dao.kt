// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/data/db/A1Dao.kt
//
// DAO для системы обучения A1.
// Ключевые запросы:
//   - next cluster to learn (планировщик сессий)
//   - weak lemmas (для system prompt Gemini)
//   - grammar rules ready to introduce (по порогу экспозиции)
//   - coverage metrics (для UI прогресса)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ════════════════════════════════════════════════════
//  LEMMAS DAO
// ════════════════════════════════════════════════════
@Dao
interface A1LemmaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lemmas: List<LemmaA1Entity>)

    @Update
    suspend fun update(lemma: LemmaA1Entity)

    @Query("SELECT * FROM a1_lemmas WHERE lemma = :lemma LIMIT 1")
    suspend fun getByLemma(lemma: String): LemmaA1Entity?

    @Query("SELECT * FROM a1_lemmas WHERE lemma IN (:lemmas)")
    suspend fun getByLemmas(lemmas: List<String>): List<LemmaA1Entity>

    /** Общее число лемм в A1. */
    @Query("SELECT COUNT(*) FROM a1_lemmas")
    suspend fun getTotalCount(): Int

    /** Число лемм, освоенных до порога (production >= 0.7). */
    @Query("SELECT COUNT(*) FROM a1_lemmas WHERE productionScore >= 0.7")
    suspend fun getMasteredCount(): Int

    /** Число лемм, которые хотя бы встречались в сессиях. */
    @Query("SELECT COUNT(*) FROM a1_lemmas WHERE timesHeard > 0")
    suspend fun getSeenCount(): Int

    /** Flow для реактивных обновлений UI. */
    @Query("SELECT COUNT(*) FROM a1_lemmas WHERE productionScore >= 0.7")
    fun observeMasteredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM a1_lemmas WHERE timesHeard > 0")
    fun observeSeenCount(): Flow<Int>

    /**
     * Самые слабые леммы — кандидаты на повторение в следующей сессии.
     * Используется Gemini в system_instruction как "target lemmas".
     */
    @Query("""
        SELECT * FROM a1_lemmas 
        WHERE timesHeard > 0 AND productionScore < 0.5 
        ORDER BY productionScore ASC, timesFailed DESC 
        LIMIT :limit
    """)
    suspend fun getWeakestLemmas(limit: Int = 10): List<LemmaA1Entity>

    /**
     * Леммы, которые давно не повторялись (SRS).
     */
    @Query("""
        SELECT * FROM a1_lemmas 
        WHERE nextReviewAt IS NOT NULL AND nextReviewAt <= :now 
        ORDER BY nextReviewAt ASC 
        LIMIT :limit
    """)
    suspend fun getDueForReview(now: Long = System.currentTimeMillis(), limit: Int = 20): List<LemmaA1Entity>

    /** Леммы, которые ещё ни разу не встречались. */
    @Query("SELECT * FROM a1_lemmas WHERE timesHeard = 0 LIMIT :limit")
    suspend fun getUnseen(limit: Int = 50): List<LemmaA1Entity>

    /**
     * Апдейт счётчиков после сессии — атомарная операция.
     * Вызывается из A1SituationSession.handleToolCall для каждой
     * леммы, которую Gemini отметил как "использована успешно".
     */
    @Query("""
        UPDATE a1_lemmas 
        SET timesHeard = timesHeard + 1,
            timesProduced = timesProduced + :produced,
            timesFailed = timesFailed + :failed,
            productionScore = MIN(1.0, productionScore + :productionDelta),
            recognitionScore = MIN(1.0, recognitionScore + :recognitionDelta),
            lastSeenAt = :now,
            lastClusterId = :clusterId,
            nextReviewAt = :nextReview
        WHERE lemma = :lemma
    """)
    suspend fun updateProgress(
        lemma: String,
        produced: Int,
        failed: Int,
        productionDelta: Float,
        recognitionDelta: Float,
        clusterId: String,
        now: Long = System.currentTimeMillis(),
        nextReview: Long,
    )
}

// ════════════════════════════════════════════════════
//  CLUSTERS DAO
// ════════════════════════════════════════════════════
@Dao
interface A1ClusterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clusters: List<ClusterA1Entity>)

    @Update
    suspend fun update(cluster: ClusterA1Entity)

    @Query("SELECT * FROM a1_clusters WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ClusterA1Entity?

    @Query("SELECT * FROM a1_clusters ORDER BY difficulty ASC, id ASC")
    suspend fun getAllOrdered(): List<ClusterA1Entity>

    @Query("SELECT * FROM a1_clusters WHERE category = :category ORDER BY difficulty ASC, id ASC")
    suspend fun getByCategory(category: String): List<ClusterA1Entity>

    /** Все уникальные категории для UI-карты тем. */
    @Query("SELECT DISTINCT category FROM a1_clusters ORDER BY category ASC")
    suspend fun getAllCategories(): List<String>

    @Query("SELECT COUNT(*) FROM a1_clusters")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM a1_clusters WHERE masteryScore >= 0.7")
    suspend fun getMasteredCount(): Int

    @Query("SELECT COUNT(*) FROM a1_clusters WHERE masteryScore >= 0.7")
    fun observeMasteredCount(): Flow<Int>

    /**
     * Следующий кластер для прохождения.
     * Логика:
     *   1) Все prerequisites пройдены (isUnlocked = true)
     *   2) Либо не пройден вообще, либо пришло время повтора (SRS)
     *   3) Сначала по difficulty, потом по количеству attempts (новые приоритетнее)
     */
    @Query("""
        SELECT * FROM a1_clusters 
        WHERE isUnlocked = 1 
          AND (masteryScore < 0.7 OR (nextReviewAt IS NOT NULL AND nextReviewAt <= :now)) 
        ORDER BY difficulty ASC, attempts ASC, id ASC 
        LIMIT 1
    """)
    suspend fun getNextCluster(now: Long = System.currentTimeMillis()): ClusterA1Entity?

    /** Все кластеры готовые к повтору по SRS. */
    @Query("""
        SELECT * FROM a1_clusters 
        WHERE nextReviewAt IS NOT NULL AND nextReviewAt <= :now 
        ORDER BY nextReviewAt ASC
    """)
    suspend fun getDueForReview(now: Long = System.currentTimeMillis()): List<ClusterA1Entity>

    /**
     * После прохождения кластера: считаем, какие кластеры теперь
     * разблокированы (все prerequisites пройдены).
     *
     * NB: это upsert-like операция — мы читаем JSON prerequisites
     * и сравниваем с mastered set. Сделано на Kotlin-стороне
     * в UseCase, здесь только raw update.
     */
    @Query("UPDATE a1_clusters SET isUnlocked = 1 WHERE id = :id")
    suspend fun markUnlocked(id: String)

    @Query("""
        UPDATE a1_clusters 
        SET attempts = attempts + 1,
            masteryScore = :masteryScore,
            lastAttemptAt = :now,
            nextReviewAt = :nextReview
        WHERE id = :id
    """)
    suspend fun updateClusterProgress(
        id: String,
        masteryScore: Float,
        now: Long = System.currentTimeMillis(),
        nextReview: Long,
    )
}

// ════════════════════════════════════════════════════
//  GRAMMAR RULES DAO
// ════════════════════════════════════════════════════
@Dao
interface A1GrammarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<GrammarRuleA1Entity>)

    @Query("SELECT * FROM a1_grammar_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): GrammarRuleA1Entity?

    @Query("SELECT * FROM a1_grammar_rules ORDER BY difficulty ASC")
    suspend fun getAllOrdered(): List<GrammarRuleA1Entity>

    @Query("SELECT * FROM a1_grammar_rules WHERE wasIntroduced = 1 ORDER BY introducedAt DESC")
    suspend fun getAllIntroduced(): List<GrammarRuleA1Entity>

    @Query("SELECT COUNT(*) FROM a1_grammar_rules WHERE wasIntroduced = 1")
    fun observeIntroducedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM a1_grammar_rules WHERE masteryScore >= 0.7")
    fun observeMasteredCount(): Flow<Int>

    /**
     * Правила, готовые к введению — те, где порог экспозиции превышен,
     * но правило ещё не было показано.
     * Gemini увидит одно такое правило за сессию (или ни одного).
     */
    @Query("""
        SELECT * FROM a1_grammar_rules 
        WHERE wasIntroduced = 0 AND timesHeardInContext >= exposureThreshold 
        ORDER BY difficulty ASC 
        LIMIT 1
    """)
    suspend fun getNextRuleToIntroduce(): GrammarRuleA1Entity?

    /** Инкремент счётчика экспозиций (Gemini услышан паттерн в речи). */
    @Query("""
        UPDATE a1_grammar_rules 
        SET timesHeardInContext = timesHeardInContext + :delta 
        WHERE id = :id
    """)
    suspend fun incrementExposure(id: String, delta: Int = 1)

    /** Отметить что правило было показано. */
    @Query("""
        UPDATE a1_grammar_rules 
        SET wasIntroduced = 1, introducedAt = :now 
        WHERE id = :id
    """)
    suspend fun markIntroduced(id: String, now: Long = System.currentTimeMillis())

    /** Обновление мастерства правила после применения в речи. */
    @Query("""
        UPDATE a1_grammar_rules 
        SET timesAppliedCorrectly = timesAppliedCorrectly + :correct,
            timesFailedOnThis = timesFailedOnThis + :failed,
            masteryScore = MIN(1.0, masteryScore + :delta) 
        WHERE id = :id
    """)
    suspend fun updateMastery(id: String, correct: Int, failed: Int, delta: Float)
}

// ════════════════════════════════════════════════════
//  SESSION LOG DAO
// ════════════════════════════════════════════════════
@Dao
interface A1SessionDao {

    @Insert
    suspend fun insert(log: A1SessionLogEntity): Long

    @Query("SELECT * FROM a1_session_logs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<A1SessionLogEntity>

    @Query("SELECT COUNT(*) FROM a1_session_logs")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM a1_session_logs WHERE startedAt >= :since")
    suspend fun getCountSince(since: Long): Int
}

// ════════════════════════════════════════════════════
//  USER PROGRESS DAO
// ════════════════════════════════════════════════════
@Dao
interface A1UserProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: A1UserProgressEntity)

    @Query("SELECT * FROM a1_user_progress WHERE userId = :userId LIMIT 1")
    suspend fun get(userId: String = "default"): A1UserProgressEntity?

    @Query("SELECT * FROM a1_user_progress WHERE userId = :userId LIMIT 1")
    fun observe(userId: String = "default"): Flow<A1UserProgressEntity?>
}
