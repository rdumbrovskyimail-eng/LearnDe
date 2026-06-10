package com.learnde.app.learn.tutor

import kotlinx.serialization.Serializable

/** Тип карточки — определяет иконку/акцент в UI. */
enum class TutorHintType {
    /** Объяснение грамматического правила + примеры. */
    GRAMMAR,

    /** «Обрати внимание» — разбор только что сделанной ошибки. */
    ATTENTION,

    /** Мини-шпаргалка по словам текущей фазы (формы, артикли, множественное). */
    VOCAB,

    /** Культурная/практическая заметка (как это звучит в жизни). */
    CULTURE,
}

/**
 * Готовая карточка для UI.
 *
 * @param id        стабильный id (dedup при повторных событиях)
 * @param type      тип карточки
 * @param title     заголовок (рус., до ~40 символов)
 * @param body      основной текст (рус., 1–3 предложения)
 * @param examples  примеры «de — ru» (0–4 шт.)
 * @param createdAt время создания (для сортировки/устаревания)
 */
data class TutorHintCard(
    val id: String,
    val type: TutorHintType,
    val title: String,
    val body: String,
    val examples: List<TutorExample> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class TutorExample(
    val de: String,
    val ru: String,
)

/**
 * JSON-схема ответа модели. Модель ОБЯЗАНА вернуть только этот объект
 * (responseMimeType = application/json гарантирует это на уровне API).
 */
@Serializable
data class TutorHintResponse(
    val title: String,
    val body: String,
    val examples: List<TutorExample> = emptyList(),
)