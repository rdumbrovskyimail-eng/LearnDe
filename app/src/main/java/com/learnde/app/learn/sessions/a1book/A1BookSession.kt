// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1book/A1BookSession.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1book

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Книжный курс A1.1 (Schritte-структура). Один голосовой урок = один Lektion.
 * Контент берётся из assets/a1_book/*.json через A1BookRepository.
 *
 * Без function-calling — чистый разговорный режим. Меньше движущихся частей =
 * меньше шансов на сбой/глюк. Завершение урока — кнопкой "Завершить" на экране.
 */
@Singleton
class A1BookSession @Inject constructor(
    private val repo: A1BookRepository,
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "a1_book"

    @Volatile
    private var prompt: String = ""

    override val systemInstruction: String
        get() = prompt

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String =
        "[СИСТЕМА]: Ученик открыл урок и готов начать. Поздоровайся и начни с первого пункта."

    override suspend fun onEnter() {
        val n = repo.selectedLesson
        prompt = runCatching {
            repo.buildPrompt(repo.loadLesson(n))
        }.getOrElse { e ->
            logger.e("A1BookSession: load lesson $n failed: ${e.message}")
            "Ты — репетитор немецкого A1. Урок не загрузился. " +
                "Скажи по-русски, что урок временно недоступен, и предложи выбрать другой."
        }
        logger.d("A1BookSession.onEnter: lesson=$n, promptLen=${prompt.length}")
    }

    override suspend fun onExit() {
        prompt = ""
    }

    override suspend fun handleToolCall(call: FunctionCall): String? = null
}
