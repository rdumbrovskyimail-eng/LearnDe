package com.learnde.app.learn.sessions.a1.v2

import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.domain.model.ParameterConfig
import com.learnde.app.learn.sessions.a1.A1FunctionDeclarations

object A1FunctionDeclarationsV2 {

    // Новые имена
    const val FN_STEP_DONE       = "step_done"
    const val FN_FLEX_MOMENT     = "flex_moment"
    const val FN_LOG_ASSOCIATION = "log_association"
    const val FN_CONTROL_SESSION = "control_session"
    const val FN_UPDATE_PROFILE  = "update_profile"

    // Унаследованные имена (для удобства обращения)
    const val FN_EVALUATE  = A1FunctionDeclarations.FN_EVALUATE_AND_UPDATE
    const val FN_HEARD     = A1FunctionDeclarations.FN_MARK_LEMMA_HEARD
    const val FN_PRODUCED  = A1FunctionDeclarations.FN_MARK_LEMMA_PRODUCED
    const val FN_GRAMMAR   = A1FunctionDeclarations.FN_INTRODUCE_GRAMMAR
    const val FN_FINISH    = A1FunctionDeclarations.FN_FINISH_SESSION

    val STEP_DONE_DECL = FunctionDeclarationConfig(
        name = FN_STEP_DONE,
        description = "Вызывай когда ТЕКУЩИЙ шаг урока ([ШАГ k/N]) реально " +
            "выполнен. Только один раз на шаг, только с id текущего шага.",
        parameters = mapOf(
            "step_id" to ParameterConfig(
                type = "STRING",
                description = "id из заголовка шага, например 's07'."
            ),
        ),
        required = listOf("step_id"),
    )

    val FLEX_MOMENT_DECL = FunctionDeclarationConfig(
        name = FN_FLEX_MOMENT,
        description = "Вызывай ОДИН раз, когда разговор с учеником " +
            "отклонился от текущего шага (вопрос, ассоциация, болтовня). " +
            "Это легально и поощряется — система даст время и сама " +
            "напомнит вернуться.",
        parameters = mapOf(
            "reason" to ParameterConfig(
                type = "STRING",
                description = "Коротко: о чём отступление ('вопрос про артикли', 'ассоциация')."
            ),
        ),
        required = listOf("reason"),
    )

    val LOG_ASSOCIATION_DECL = FunctionDeclarationConfig(
        name = FN_LOG_ASSOCIATION,
        description = "ОБЯЗАТЕЛЬНО вызывай, когда ученик придумал/рассказал " +
            "ассоциацию, образ, мнемонику или личную историю к слову. " +
            "Она сохранится и станет его персональной подсказкой в " +
            "будущих уроках.",
        parameters = mapOf(
            "lemma" to ParameterConfig(
                type = "STRING",
                description = "Базовая форма леммы ('Haus', не 'Häuser')."
            ),
            "association" to ParameterConfig(
                type = "STRING",
                description = "Ассоциация словами ученика, 1 фраза по-русски."
            ),
        ),
        required = listOf("lemma", "association"),
    )

    val CONTROL_SESSION_DECL = FunctionDeclarationConfig(
        name = FN_CONTROL_SESSION,
        description = "Управление сессией ГОЛОСОМ ученика. Вызывай когда " +
            "ученик явно просит: паузу, стоп, продолжить, пропустить.",
        parameters = mapOf(
            "action" to ParameterConfig(
                type = "STRING",
                description = "PAUSE — ученик просит паузу. " +
                    "RESUME — продолжить после паузы. " +
                    "STOP — закончить обучение совсем (попрощайся ДО вызова). " +
                    "SKIP_STEP — пропустить текущий шаг/слово. " +
                    "SKIP_BREAK — (слепой режим) начать следующий урок сразу."
            ),
        ),
        required = listOf("action"),
    )

    val UPDATE_PROFILE_DECL = FunctionDeclarationConfig(
        name = FN_UPDATE_PROFILE,
        description = "Вызывай если ученик представился (запомнить имя) или " +
            "рассказал о своих интересах/хобби — они улучшат примеры в уроках.",
        parameters = mapOf(
            "name" to ParameterConfig(
                type = "STRING",
                description = "Имя ученика (пусто если не менялось)."
            ),
            "interests" to ParameterConfig(
                type = "STRING",
                description = "Интересы через запятую (пусто если не менялись)."
            ),
        ),
        required = listOf("name", "interests"),
    )

    /** Полный набор для учебной сессии v2. */
    val ALL: List<FunctionDeclarationConfig> = listOf(
        STEP_DONE_DECL,
        FLEX_MOMENT_DECL,
        LOG_ASSOCIATION_DECL,
        CONTROL_SESSION_DECL,
        UPDATE_PROFILE_DECL,
        A1FunctionDeclarations.EVALUATE_AND_UPDATE_DECL,
        A1FunctionDeclarations.MARK_LEMMA_HEARD_DECL,
        A1FunctionDeclarations.MARK_LEMMA_PRODUCED_DECL,
        A1FunctionDeclarations.INTRODUCE_GRAMMAR_DECL,
        A1FunctionDeclarations.FINISH_SESSION_DECL,
    )
}