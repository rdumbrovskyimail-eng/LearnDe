// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSession.kt
//
// Обобщённая абстракция учебной сессии.
//
// В один момент времени активна МАКСИМУМ ОДНА сессия
// (управляется через LearnSessionController).
//
// Чтобы добавить новый тест:
//   1. Создать class FooLearnSession : LearnSession
//   2. Привязать его в Hilt (@Singleton + @Inject constructor)
//   3. Зарегистрировать в LearnSessionRegistry
//   4. Создать UI-экран, который триггерит learnController.enter(foo)
//
// VoiceViewModel, GeminiLiveClient, ToolRegistry ТРОГАТЬ НЕ НУЖНО.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig

/**
 * Контракт учебной сессии.
 *
 * Сессия:
 *  - задаёт свой systemInstruction (Gemini прочитает его при подключении);
 *  - задаёт список своих function declarations (отправятся в setup.tools);
 *  - обрабатывает tool calls (возвращает result JSON или null → делегат
 *    в ToolRegistry);
 *  - публикует state через собственные компоненты (ViewModel/Bus).
 */
interface LearnSession {

    /** Уникальный ID сессии, используется для регистрации. */
    val id: String

    /** systemInstruction, которую получит Gemini при SetupComplete. */
    val systemInstruction: String

    /** Function declarations для сессии (setup.tools.functionDeclarations). */
    val functionDeclarations: List<FunctionDeclarationConfig>

    /**
     * Первая фраза, которую клиент пошлёт модели сразу после SetupComplete.
     * Пусто = ничего не отправляем, модель сама начнёт.
     */
    val initialUserMessage: String

    /**
     * Вызывается при активации сессии (после прошлой onExit, до reconnect).
     * Здесь — сброс внутреннего state.
     */
    suspend fun onEnter()

    /**
     * Вызывается при деактивации сессии (до нового enter или при exit).
     */
    suspend fun onExit()

    /**
     * Обработать tool call.
     *
     * @return  result-строка (JSON) если это "наша" функция,
     *          null если функция не обрабатывается этой сессией
     *          (VoiceViewModel делегирует в ToolRegistry).
     */
    suspend fun handleToolCall(call: FunctionCall): String?
}
