// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1FunctionDeclarations.kt
//
// ИЗМЕНЕНИЯ:
//   - FN_EVALUATE_AND_UPDATE расширен до диагностики Selinker
//   - Добавлены 4 новых параметра: error_source, error_depth,
//     error_category, error_specifics
//   - Старое поле was_produced_correctly оставлено для совместимости,
//     но теперь вычисляется по error_depth (NONE == correct)
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

    val START_PHASE_DECL = FunctionDeclarationConfig(
        name = FN_START_PHASE,
        description = "Вызывай перед началом каждой фазы сессии.",
        parameters = mapOf(
            "phase" to ParameterConfig(
                type = "STRING",
                description = "WARM_UP, INTRODUCE, DRILL, APPLY, GRAMMAR, или COOL_DOWN."
            )
        ),
        required = listOf("phase")
    )

    val MARK_LEMMA_HEARD_DECL = FunctionDeclarationConfig(
        name = FN_MARK_LEMMA_HEARD,
        description = "Вызывай КАЖДЫЙ раз, когда используешь целевую лемму в своей речи.",
        parameters = mapOf(
            "lemma" to ParameterConfig(
                type = "STRING",
                description = "Базовая форма леммы (например 'Haus', не 'Häuser')."
            )
        ),
        required = listOf("lemma")
    )

    val MARK_LEMMA_PRODUCED_DECL = FunctionDeclarationConfig(
        name = FN_MARK_LEMMA_PRODUCED,
        description = "Вызывай когда ученик успешно использовал лемму в своей речи.",
        parameters = mapOf(
            "lemma" to ParameterConfig(type = "STRING", description = "Базовая форма леммы."),
            "quality" to ParameterConfig(type = "INTEGER", description = "Качество 1-7.")
        ),
        required = listOf("lemma", "quality")
    )

    // ═══════════════════════════════════════════════════════════════
    //  ГЛАВНОЕ ИЗМЕНЕНИЕ: EVALUATE_AND_UPDATE с диагностикой Selinker
    // ═══════════════════════════════════════════════════════════════
    val EVALUATE_AND_UPDATE_DECL = FunctionDeclarationConfig(
        name = FN_EVALUATE_AND_UPDATE,
        description = """
            Вызывай после каждого ответа ученика в фазе DRILL или APPLY.
            Проведи ДИАГНОСТИКУ ошибки по модели Selinker (interlanguage).
            Если ошибки нет — все поля error_* должны быть NONE.
        """.trimIndent(),
        parameters = mapOf(
            "lemma" to ParameterConfig(
                type = "STRING",
                description = "Лемма, которую ученик пытался использовать."
            ),
            "quality" to ParameterConfig(
                type = "INTEGER",
                description = "Оценка 1-7 по качеству ответа."
            ),
            "error_source" to ParameterConfig(
                type = "STRING",
                description = """
                    Источник ошибки (ось 1 модели Selinker). Значения:
                    NONE — ошибки нет.
                    L1_TRANSFER — влияние русского языка (пропуск артикля, нет падежа).
                    OVERGENERALIZATION — применил правило слишком широко (напр. 'gehte' вместо 'ging').
                    SIMPLIFICATION — упростил форму (пропустил окончание, 'ich gehen').
                    COMMUNICATION_STRATEGY — сознательный обход (перефразирование, жест).
                """.trimIndent()
            ),
            "error_depth" to ParameterConfig(
                type = "STRING",
                description = """
                    Глубина ошибки (ось 2 модели Selinker). Значения:
                    NONE — ошибки нет.
                    SLIP — ученик знает правило, оговорился (под стрессом, в шуме).
                    MISTAKE — ученик неуверен, может исправить при наводящем вопросе.
                    ERROR — ученик НЕ знает правила вообще, требуется явное объяснение.
                """.trimIndent()
            ),
            "error_category" to ParameterConfig(
                type = "STRING",
                description = """
                    Конкретная категория ошибки. Значения:
                    NONE, GENDER, CASE, WORD_ORDER, LEXICAL, PHONOLOGY,
                    PRAGMATICS, CONJUGATION, NEGATION, PLURAL, PREPOSITION.
                """.trimIndent()
            ),
            "error_specifics" to ParameterConfig(
                type = "STRING",
                description = "Конкретика на русском, 1 фраза: 'использовал Nominativ вместо Akkusativ после haben'. Пусто если ошибки нет."
            ),
            "feedback" to ParameterConfig(
                type = "STRING",
                description = "Краткий фидбек на русском для ученика (1 предложение)."
            )
        ),
        required = listOf(
            "lemma", "quality"
        )
    )

    val INTRODUCE_GRAMMAR_DECL = FunctionDeclarationConfig(
        name = FN_INTRODUCE_GRAMMAR,
        description = "Вызывай ТОЛЬКО ОДИН РАЗ за сессию — когда объясняешь новое правило.",
        parameters = mapOf(
            "rule_id" to ParameterConfig(
                type = "STRING",
                description = "ID правила (например 'g08_akkusativ')."
            )
        ),
        required = listOf("rule_id")
    )

    val FINISH_SESSION_DECL = FunctionDeclarationConfig(
        name = FN_FINISH_SESSION,
        description = "Вызывай ПОСЛЕДНИМ — когда сессия полностью завершена.",
        parameters = mapOf(
            "overall_quality" to ParameterConfig(
                type = "INTEGER",
                description = "Общая оценка 1-7."
            ),
            "feedback" to ParameterConfig(
                type = "STRING",
                description = "Итог на русском (1-2 предложения)."
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