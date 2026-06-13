package com.learnde.app.learn.data.db.v2

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Личная ассоциация ученика к лемме.
 * Несколько ассоциаций на лемму допустимы — берём последнюю
 * (самую свежую) как основную подсказку.
 */
@Entity(
    tableName = "a1_associations",
    indices = [Index(value = ["lemma"])],
)
data class A1AssociationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lemma: String,
    /** Текст ассоциации словами ученика: «Brille — бриллиант на носу». */
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Сколько раз ассоциация реально помогла вспомнить (для рейтинга). */
    val timesHelped: Int = 0,
)

/**
 * Снимок LessonScript. isFinished=false ровно у одного плана
 * (активного) — это инвариант, который поддерживает A1LessonPlanDao.upsert
 * + LessonDirector (новый start закрывает старый план через markAllFinished).
 */
@Entity(tableName = "a1_lesson_plans")
data class LessonPlanStateEntity(
    @PrimaryKey val planId: String,
    val clusterId: String,
    /** Полный сериализованный LessonScript (kotlinx.serialization). */
    val scriptJson: String,
    /** Дублируем курсор колонкой — для быстрых запросов/отладки. */
    val cursor: Int,
    val isFinished: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
)

/**
 * Профиль ученика. Одна запись (id="default").
 */
@Entity(tableName = "a1_learner_profile")
data class LearnerProfileEntity(
    @PrimaryKey val id: String = "default",
    val displayName: String = "",
    /** Интересы через запятую: «футбол, готовка, IT». Пополняет модель через update_profile. */
    val interestsCsv: String = "",
    /** RELAXED / NORMAL / FOCUSED — см. FlexTalkPolicy.PaceLevel. */
    val paceLevel: String = "NORMAL",

    // ─── Слепой режим ───
    /** Максимум уроков подряд в одной слепой цепочке. */
    val blindMaxChainedSessions: Int = 8,
    /** Пауза между уроками цепочки, сек. */
    val blindBreakSeconds: Int = 12,
    /** Каждый k-й урок цепочки — чистое повторение (interleaving цепочки). */
    val blindReviewEvery: Int = 3,

    // ─── Статистика ───
    val totalBlindSessions: Int = 0,
    val totalFlexMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
)