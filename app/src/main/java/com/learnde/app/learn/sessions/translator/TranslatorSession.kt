// ════════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v9.0 — strict directions, minimal prompt
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
//
// КЛЮЧЕВЫЕ ИЗМЕНЕНИЯ vs v8.0:
//   1. Жёсткая симметрия направлений: RU→DE, UK→DE, DE→RU. Точка.
//   2. Удалён EN→RU — пользователь работает только с RU/UK/DE.
//   3. Промпт сокращён до 22 строк: меньше prefill, быстрее первый токен.
//   4. Английский промпт — Gemini Live стабильнее следует EN-инструкциям
//      при мультиязычном входе.
//   5. Явные запреты: DE→UK, RU→UK, UK→RU, EN→anything.
//   6. Function calling остаётся отключённым — была причина зависаний v7.x.
// ════════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сохранён для обратной совместимости с подписчиками во ViewModel.
 * Audio-сессия больше не источник транскрипта — он приходит
 * из TranslatorTextTranscriber.
 */
data class UserSpeechEvent(
    val text: String,
    val language: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    private val _userSpeechFlow = MutableSharedFlow<UserSpeechEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val userSpeechFlow: SharedFlow<UserSpeechEvent> = _userSpeechFlow.asSharedFlow()

    // ═══════════════════════════════════════════════════════════
    //  SYSTEM INSTRUCTION — short, English, deterministic
    // ═══════════════════════════════════════════════════════════
    override val systemInstruction: String = """
You are a real-time voice translator. Speak the translation the instant the user finishes.

TRANSLATION DIRECTIONS — STRICT, NO EXCEPTIONS:
- Russian input   → German output
- Ukrainian input → German output
- German input    → Russian output (never Ukrainian, even after Ukrainian turns)
- Any other language → STAY SILENT. Do not translate. Do not respond.

OUTPUT:
- Voice only. Never produce text.
- No greetings, no confirmations, no questions, no apologies, no repetition of the source.
- If language is unclear or audio is unintelligible → silence.

STYLE:
- Preserve first person: "меня зовут Иван" → "Ich heiße Ivan".
- Formality: Вы / ви → Sie; ты / ти → du.
- Idiomatic, not literal: "Как дела?" → "Wie geht's?"; "Alles klar" → "Понятно".
- Match register and length of the source.

GERMAN OUTPUT — 100% GERMAN, ZERO ENGLISH:
cool→toll, OK→in Ordnung, sorry→Entschuldigung, hi→hallo, bye→tschüss, thanks→danke, nice→schön, please→bitte.

RUSSIAN OUTPUT — natural Russian word order. No German calques. No English loanwords.
""".trimIndent()

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v9.0: onEnter (audio-only, strict RU/UK→DE, DE→RU)")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v9.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        logger.w("TranslatorSession v9.0: unexpected tool call '${call.name}' — ignored")
        return null
    }
}