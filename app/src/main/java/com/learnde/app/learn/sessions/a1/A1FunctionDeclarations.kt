// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1FunctionDeclarations.kt
//
// Объявления функций, которые Gemini может вызывать во время
// учебной сессии A1. Каждый вызов = сигнал для БД и UI.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.domain.model.ParameterConfig

object A1FunctionDeclarations {

    const val FN_START_PHASE          = "start_phase"
    const val FN_MARK_LEMMA_HEARD     = "mark_lemma_heard"
    const val FN_MARK_LEMMA_PRODUCED  = "mark_lemma_produced"
    const val FN_EVALUATE_AND_UPDATE  = "evaluate_and_update_lemma"
    const val FN_INTRODUCE_GRAMMAR    = "introduce_grammar_rule"
    const val FN_FINISH_SESSION       = "finish_session"

    // ─── Фазы сессии ───
    val START_PHASE_DECL = FunctionDeclarationConfig(
        name = FN_START_PHASE,
        description = "Вызывай перед началом каждой фазы сессии. Это обновит UI и покажет ученику прогресс по фазам.",
        parameters = mapOf(
            "phase" to ParameterConfig(
                type = "STRING",
                description = "Имя фазы: WARM_UP, INTRODUCE, DRILL, APPLY, GRAMMAR, или COOL_DOWN."
            )
        ),
        required = listOf("phase")
    )

    // ─── Лемма услышана (в речи Gemini) ───
    val MARK_LEMMA_HEARD_DECL = FunctionDeclarationConfig(
        name = FN_MARK_LEMMA_HEARD,
        description = "Вызывай КАЖДЫЙ раз, когда используешь целевую лемму в своей речи (для SRS-трекинга).",
        parameters = mapOf(
            "lemma" to ParameterConfig(
                type = "STRING",
                description = "Базовая форма леммы (например 'Haus', не 'Häuser')."
            )
        ),
        required = listOf("lemma")
    )

    // ─── Лемма произведена учеником ───
    val MARK_LEMMA_PRODUCED_DECL = FunctionDeclarationConfig(
        name = FN_MARK_LEMMA_PRODUCED,
        description = "Вызывай когда ученик успешно использовал лемму в своей речи (без оценки правильности).",
        parameters = mapOf(
            "lemma" to ParameterConfig(
                type = "STRING",
                description = "Базовая форма леммы."
            ),
            "quality" to ParameterConfig(
                type = "INTEGER",
                description = "Качество 1-7 по нашей шкале."
            )
        ),
        required = listOf("lemma", "quality")
    )

    // ─── Полная оценка: лемма + прогресс ───
    val EVALUATE_AND_UPDATE_DECL = FunctionDeclarationConfig(
        name = FN_EVALUATE_AND_UPDATE,
        description = "Вызывай после каждого ответа ученика в фазе DRILL. Оцени его попытку употребить лемму.",
        parameters = mapOf(
            "lemma" to ParameterConfig(
                type = "STRING",
                description = "Лемма, которую ученик пытался использовать."
            ),
            "quality" to ParameterConfig(
                type = "INTEGER",
                description = "Оценка 1-7."
            ),
            "was_produced_correctly" to ParameterConfig(
                type = "BOOLEAN",
                description = "true если ученик правильно произнёс/применил лемму."
            ),
            "feedback" to ParameterConfig(
                type = "STRING",
                description = "Краткий фидбек на русском (1 предложение)."
            )
        ),
        required = listOf("lemma", "quality", "was_produced_correctly")
    )

    // ─── Правило грамматики представлено ───
    val INTRODUCE_GRAMMAR_DECL = FunctionDeclarationConfig(
        name = FN_INTRODUCE_GRAMMAR,
        description = "Вызывай ТОЛЬКО ОДИН РАЗ за сессию — когда объясняешь новое правило грамматики в фазе GRAMMAR.",
        parameters = mapOf(
            "rule_id" to ParameterConfig(
                type = "STRING",
                description = "ID правила (например 'g08_akkusativ')."
            )
        ),
        required = listOf("rule_id")
    )

    // ─── Завершение сессии ───
    val FINISH_SESSION_DECL = FunctionDeclarationConfig(
        name = FN_FINISH_SESSION,
        description = "Вызывай ПОСЛЕДНИМ — когда сессия полностью завершена (после COOL_DOWN).",
        parameters = mapOf(
            "overall_quality" to ParameterConfig(
                type = "INTEGER",
                description = "Общая оценка всей сессии 1-7."
            ),
            "feedback" to ParameterConfig(
                type = "STRING",
                description = "Короткий итог на русском (1-2 предложения): что получилось, что повторить."
            )
        ),
        required = listOf("overall_quality", "feedback")
    )

    val ALL: List<FunctionDeclarationConfig> = listOf(
        START_PHASE_DECL,
        MARK_LEMMA_HEARD_DECL,
        MARK_LEMMA_PRODUCED_DECL,
        EVALUATE_AND_UPDATE_DECL,
        INTRODUCE_GRAMMAR_DECL,
        FINISH_SESSION_DECL,
    )
}
