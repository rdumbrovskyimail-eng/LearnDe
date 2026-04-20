// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestRegistry.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.domain.model.ParameterConfig

object A0a1TestRegistry {

    // ───── Константы теста ─────
    const val MAX_POINTS_PER_QUESTION = 7
    const val A0_QUESTIONS = 10
    const val STANDARD_QUESTIONS = 20

    const val A0_MAX_POINTS = 70
    const val STANDARD_MAX_POINTS = 140

    const val A0_THRESHOLD = 46 
    const val STANDARD_THRESHOLD = 91

    // ───── Имена функций ─────
    const val FN_ASK_QUESTION = "ask_question"
    const val FN_EVALUATE     = "evaluate_answer"
    const val FN_FINISH       = "finish_test"

    // ───── Декларации ─────
    val ASK_QUESTION_DECLARATION = FunctionDeclarationConfig(
        name = FN_ASK_QUESTION,
        description = "Вызови эту функцию ПЕРЕД тем, как произнести новый вопрос вслух. Это выведет текст вопроса на экран ученику.",
        parameters = mapOf(
            "text" to ParameterConfig(type = "STRING", description = "Точный текст вопроса, который ты сейчас задашь."),
            "index" to ParameterConfig(type = "INTEGER", description = "Номер вопроса по порядку (начиная с 1).")
        ),
        required = listOf("text", "index")
    )

    val EVALUATE_DECLARATION = FunctionDeclarationConfig(
        name = FN_EVALUATE,
        description = "Вызови эту функцию СРАЗУ после ответа пользователя, чтобы оценить его по 7-балльной шкале и вывести детальный разбор на экран.",
        parameters = mapOf(
            "points" to ParameterConfig(type = "INTEGER", description = "Оценка строго от 1 до 7."),
            "is_correct" to ParameterConfig(type = "BOOLEAN", description = "Ответил ли ученик верно по сути (true) или ошибся/не знал (false)."),
            "reason" to ParameterConfig(type = "STRING", description = "Объяснение на русском: в чем именно ошибка или почему ответ правильный. 1-2 предложения."),
            "score_rationale" to ParameterConfig(type = "STRING", description = "Обоснование балла: за что снижено/начислено (произношение, грамматика и тд)."),
            "feedback" to ParameterConfig(type = "STRING", description = "Краткий комментарий для логов.")
        ),
        required = listOf("points", "is_correct", "reason", "score_rationale", "feedback")
    )

    val FINISH_DECLARATION = FunctionDeclarationConfig(
        name = FN_FINISH,
        description = "Вызови эту функцию ТОЛЬКО когда задал все вопросы и оценил последний.",
        parameters = emptyMap(),
        required = emptyList()
    )

    val ALL_DECLARATIONS = listOf(ASK_QUESTION_DECLARATION, EVALUATE_DECLARATION, FINISH_DECLARATION)

    // ───── Базовые правила (общие для всех) ─────
    private const val EVALUATION_RULES = """
        ШКАЛА ОЦЕНОК (КРИТИЧЕСКИ ВАЖНО - СТРОГО ОТ 1 ДО 7 БАЛЛОВ):
        Оценивай каждый ответ пользователя исключительно по этой шкале:
        - 1 балл: Ученик говорит "Я не знаю" (или сдается) на русском или другом языке.
        - 2 балла: Ученик говорит "Я не знаю" на немецком языке (например, "Ich weiß nicht").
        - 3 балла: Ученик отвечает ПРАВИЛЬНО по смыслу, но на РУССКОМ языке.
        - 4 балла: Ученик отвечает на немецком, но предложение СОВСЕМ НЕВЕРНОЕ (по смыслу или грамматике грубейшая ошибка).
        - 5 баллов: Ученик отвечает верно по-немецки, но использует НЕПОЛНУЮ конструкцию (например, короткий ответ из 1 слова).
        - 6 баллов: Ученик говорит полную конструкцию по-немецки, но делает несколько мелких ошибок, говорит нечетко.
        - 7 баллов: Идеальный ответ. Конструкция четкая, верная, выразительная и полная.
    """

    private const val ALGORITHM_RULES = """
        СТРОГИЙ АЛГОРИТМ ТВОИХ ДЕЙСТВИЙ (Выполняй именно в таком порядке!):
        ШАГ 1: Как только готов задать вопрос, СНАЧАЛА вызови функцию `ask_question`, передав текст вопроса и его номер.
        ШАГ 2: Сразу после этого произнеси голосом: "Вопрос номер [номер]..." и задай вопрос вслух. Жди ответа.
        ШАГ 3: Когда ученик ответил, СНАЧАЛА вызови функцию `evaluate_answer`, честно заполнив все поля (оценка, правильность, причина, обоснование).
        ШАГ 4: Затем ГОЛОСОМ по-русски дай развернутый фидбек. ЕСЛИ УЧЕНИК ОШИБСЯ (оценка 1-6) — ты ОБЯЗАН вслух сказать в чем ошибка и ГРОМКО НАЗВАТЬ ПРАВИЛЬНЫЙ ПОЛНЫЙ ОТВЕТ НА НЕМЕЦКОМ. Если оценка 7 — просто похвали.
        ШАГ 5: Переходи к следующему вопросу, начиная цикл заново с ШАГА 1.
    """

    // ───── ПРОМПТЫ УРОВНЕЙ ─────
    val A0_SYSTEM_INSTRUCTION = """
        Ты — строгий экзаменатор. Проведи устный тест на уровень A0 (БАЗА). 10 вопросов.
        $EVALUATION_RULES
        ГЕНЕРАЦИЯ: Максимально простые вопросы (цифры, цвета, как дела, простые глаголы).
        ЧЕРЕДОВАНИЕ: Нечетные вопросы задавай по-русски (ученик переводит). Четные — по-немецки.
        $ALGORITHM_RULES
        НАЧАЛО: Начни сам прямо сейчас. Скажи "Привет! Начинаем тест A0" и переходи к ШАГУ 1 для первого вопроса.
    """.trimIndent()

    val A1_SYSTEM_INSTRUCTION = """
        Ты — строгий экзаменатор. Проведи устный тест на уровень A1. 20 вопросов.
        $EVALUATION_RULES
        ГЕНЕРАЦИЯ: Темы (рассказ о себе, хобби, еда, модальные глаголы, прошедшее время Perfekt).
        ЧЕРЕДОВАНИЕ: Нечетные вопросы задавай по-русски. Четные — по-немецки.
        $ALGORITHM_RULES
        НАЧАЛО: Начни сам. Скажи "Отлично! Уровень A1" и переходи к ШАГУ 1 для первого вопроса.
    """.trimIndent()

    val A2_SYSTEM_INSTRUCTION = """
        Ты — строгий экзаменатор. Проведи устный тест на уровень A2. 20 вопросов.
        $EVALUATION_RULES
        ГЕНЕРАЦИЯ: Темы (прошедшее время Präteritum, будущее Futur I, придаточные weil/dass/wenn, визит к врачу, путешествия).
        ЧЕРЕДОВАНИЕ: Нечетные вопросы задавай по-русски. Четные — по-немецки.
        $ALGORITHM_RULES
        НАЧАЛО: Начни сам. Скажи "Супер! Уровень A2" и переходи к ШАГУ 1 для первого вопроса.
    """.trimIndent()

    val B1_SYSTEM_INSTRUCTION = """
        Ты — строгий экзаменатор. Проведи устный тест на уровень B1. 20 вопросов.
        $EVALUATION_RULES
        ГЕНЕРАЦИЯ: Темы (Plusquamperfekt, Passiv, Konjunktiv II, относительные предложения, новости, аргументация).
        ЧЕРЕДОВАНИЕ: Нечетные вопросы задавай по-русски. Четные — по-немецки.
        $ALGORITHM_RULES
        НАЧАЛО: Начни сам. Скажи "Потрясающе! Переходим к уровню B1" и переходи к ШАГУ 1.
    """.trimIndent()

    val B2_SYSTEM_INSTRUCTION = """
        Ты — строгий экзаменатор. Проведи устный тест на уровень B2 (ПРОДВИНУТЫЙ). 20 вопросов.
        $EVALUATION_RULES
        ГЕНЕРАЦИЯ: Темы (Passiv mit Modalverben, Konjunktiv I для косвенной речи, Nomen-Verb-Verbindungen, экология, политика, идиомы). Требуй сложных конструкций!
        ЧЕРЕДОВАНИЕ: Нечетные вопросы задавай по-русски. Четные — по-немецки.
        $ALGORITHM_RULES
        НАЧАЛО: Начни сам. Скажи "Финальный босс! Уровень B2" и переходи к ШАГУ 1.
    """.trimIndent()
}