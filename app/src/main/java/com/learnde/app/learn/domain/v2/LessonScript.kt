package com.learnde.app.learn.domain.v2

import kotlinx.serialization.Serializable

/**
 * Тип шага урока. Каждый тип имеет:
 *  - softLimitMs — мягкий лимит: после него Director начинает
 *    деликатно напоминать модели вернуться к графику;
 *  - hardLimitMs — жёсткий лимит: Director повторно отправляет
 *    инструкцию шага (страховка от «модель потеряла нить»).
 *
 * Лимиты НЕ обрывают общение. Они лишь управляют напоминаниями —
 * это и есть «пластичность + график».
 */
@Serializable
enum class StepKind(
    val softLimitMs: Long,
    val hardLimitMs: Long,
    val titleRu: String,
) {
    /** Тёплое приветствие + анонс плана одной фразой. */
    GREETING(soft(45), hard(120), "Привет"),

    /** Spaced retrieval старого слова (FSRS due / weakest). */
    RECALL_OLD(soft(60), hard(180), "Повтор"),

    /** Введение ОДНОГО нового слова: перевод + микро-контекст. */
    INTRODUCE(soft(75), hard(240), "Новое слово"),

    /**
     * Эхо-фаза: ученик повторяет слово вслух. Произношение можно
     * тренировать СКОЛЬКО УГОДНО — шаг закрывается только когда
     * ученик доволен (модель спрашивает «идём дальше?»).
     */
    ECHO(soft(90), hard(300), "Произношение"),

    /** Извлечение нового слова из памяти (RU→DE), введённого 2-3 шага назад. */
    RETRIEVE_NEW(soft(60), hard(180), "Вспомни"),

    /** Ученик строит мини-фразу с новым словом. */
    USE_IN_CONTEXT(soft(90), hard(240), "Фраза"),

    /**
     * FLEX-окно: свободное общение о пройденных словах.
     * Ассоциации, «где это встречается», личные истории.
     * Модель обязана вызвать log_association, если ученик
     * придумал ассоциацию — она сохранится и вернётся в
     * будущих уроках как персональная подсказка.
     */
    FLEX(soft(150), hard(360), "Свободно"),

    /** Мини-диалог по сценарию кластера (2-4 обмена). */
    MICRO_DIALOG(soft(150), hard(420), "Диалог"),

    /** Введение грамматического правила (если запланировано). */
    GRAMMAR_SPOT(soft(120), hard(300), "Грамматика"),

    /** Финальное извлечение ВСЕХ новых слов урока. */
    FINAL_RECALL(soft(120), hard(300), "Закрепление"),

    /** Итог одной фразой + finish_session. */
    WRAP_UP(soft(45), hard(120), "Итог");
}

// Top-level: enum-константы не могут обращаться к companion при инициализации.
private fun soft(sec: Int) = sec * 1000L
private fun hard(sec: Int) = sec * 1000L

/** Статус шага. Переходы только вперёд: PENDING → ACTIVE → DONE/SKIPPED. */
@Serializable
enum class StepStatus { PENDING, ACTIVE, DONE, SKIPPED }

/**
 * Один шаг сценария.
 *
 * @param id          Стабильный id "s01".."sNN" — модель обязана вернуть
 *                    его в step_done(step_id). Director принимает только
 *                    id ТЕКУЩЕГО шага (идемпотентность: дубликаты и
 *                    устаревшие id молча поглощаются).
 * @param lemma       Целевая лемма шага (null для GREETING/FLEX/DIALOG/WRAP_UP).
 * @param lemmaRu     Русский перевод для инструкции модели.
 * @param instruction Готовая инструкция для модели — Director отправит
 *                    её как "[ШАГ k/N | KIND] …". Генерируется планировщиком
 *                    один раз → шаги детерминированы и воспроизводимы.
 */
@Serializable
data class LessonStep(
    val id: String,
    val kind: StepKind,
    val lemma: String? = null,
    val lemmaRu: String? = null,
    val ruleId: String? = null,
    val instruction: String,
    val status: StepStatus = StepStatus.PENDING,
    val activatedAt: Long = 0L,
    val completedAt: Long = 0L,
)

/**
 * Полный сценарий урока.
 *
 * Инварианты (проверяются в LessonDirector):
 *  1. cursor монотонно растёт: 0 → steps.size.
 *  2. Ровно один ACTIVE-шаг (steps[cursor]) либо ни одного (урок закончен).
 *  3. Каждое изменение cursor немедленно персистится в Room
 *     (LessonPlanStateEntity) — урок переживает crash/kill/reconnect.
 */
@Serializable
data class LessonScript(
    /** Уникальный id плана = "plan_<clusterId>_<startedAt>". */
    val planId: String,
    val clusterId: String,
    val clusterTitleRu: String,
    val scenarioHint: String,
    /** Леммы, вводимые в этом уроке (для FINAL_RECALL и статистики). */
    val newLemmas: List<String>,
    /** Леммы на повторение (FSRS). */
    val reviewLemmas: List<String>,
    val steps: List<LessonStep>,
    val cursor: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isFinished: Boolean get() = cursor >= steps.size
    val currentStep: LessonStep? get() = steps.getOrNull(cursor)
    val totalSteps: Int get() = steps.size

    val progressFraction: Float
        get() = if (steps.isEmpty()) 0f else (cursor.toFloat() / steps.size).coerceIn(0f, 1f)

    /** Сколько шагов какого типа осталось — для UI-таймлайна. */
    fun remainingOfKind(kind: StepKind): Int =
        steps.drop(cursor).count { it.kind == kind }

    /** Активировать текущий шаг (выставить ACTIVE + timestamp). */
    fun withCurrentActivated(now: Long = System.currentTimeMillis()): LessonScript {
        val step = currentStep ?: return this
        if (step.status == StepStatus.ACTIVE) return this
        val updated = steps.toMutableList()
        updated[cursor] = step.copy(status = StepStatus.ACTIVE, activatedAt = now)
        return copy(steps = updated)
    }

    /**
     * Завершить текущий шаг и сдвинуть курсор. Единственная операция,
     * двигающая хронологию. Возвращает новый script.
     */
    fun withCurrentCompleted(
        skipped: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): LessonScript {
        val step = currentStep ?: return this
        val updated = steps.toMutableList()
        updated[cursor] = step.copy(
            status = if (skipped) StepStatus.SKIPPED else StepStatus.DONE,
            completedAt = now,
        )
        return copy(steps = updated, cursor = cursor + 1)
    }
}