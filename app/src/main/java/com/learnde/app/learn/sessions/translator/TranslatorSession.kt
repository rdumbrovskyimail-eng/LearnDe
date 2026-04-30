// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v7.0 — minimal prompt, parallel function call
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.domain.model.ParameterConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

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

    override val systemInstruction: String = """
You are a real-time voice translator. SPEAK THE TRANSLATION INSTANTLY.

DIRECTIONS:
RU→DE, UK→DE, DE→RU, EN→RU.
Output language MUST differ from input. Never DE→UK. Never RU→UK.

EVERY USER UTTERANCE — DO BOTH IN PARALLEL:
A) Call submit_user_speech(text, language) with exact transcript and ISO code (ru/uk/de/en/unknown).
B) IMMEDIATELY speak the translation aloud. Do NOT wait for the function response. Audio and function call run together.

SPEED RULES:
- Begin speaking translation within 200ms of user finishing.
- Never pause to think. Translate reflexively.
- Never output text-only responses. Always voice.
- Never end a turn without audio (unless input is unintelligible).

QUALITY RULES:
- First person preserved: "меня зовут Иван" → "Ich heiße Ivan".
- Formality: Вы/ви→Sie, ты/ти→du.
- Idiomatic, not literal: "Как дела?"→"Wie geht's?".
- Match length and register exactly.

GERMAN OUTPUT — 100% GERMAN, ZERO ENGLISH:
cool→toll, OK→in Ordnung, sorry→Entschuldigung, hi→hallo, bye→tschüss, thanks→danke, nice→schön.

RUSSIAN OUTPUT — NATURAL RUSSIAN:
No German word-order calques. "Ich freue mich"→"Я рад". Use proper aspect and prepositions.

NEVER:
- Speak first.
- Greet, explain, ask questions, comment.
- Mix languages in one sentence.
- Invent missing words. Unintelligible→submit_user_speech with text="..." language="unknown", stay silent.
""".trimIndent()

    override val functionDeclarations: List<FunctionDeclarationConfig> = listOf(
        FunctionDeclarationConfig(
            name = "submit_user_speech",
            description = "Report user transcript. Call in parallel with speaking the translation.",
            parameters = mapOf(
                "text" to ParameterConfig(
                    type = "STRING",
                    description = "Exact transcript in original script.",
                ),
                "language" to ParameterConfig(
                    type = "STRING",
                    description = "ISO: ru, uk, de, en, unknown.",
                ),
            ),
            required = listOf("text", "language"),
        ),
    )

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v7.0: onEnter")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v7.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            "submit_user_speech" -> {
                val text = (call.args["text"] as? String)?.trim().orEmpty()
                val lang = (call.args["language"] as? String)?.trim()?.lowercase().orEmpty()

                if (text.isNotEmpty() && text != "...") {
                    logger.d("TranslatorSession v7.0: [$lang] $text")
                    _userSpeechFlow.tryEmit(
                        UserSpeechEvent(text = text, language = lang.ifEmpty { "unknown" })
                    )
                }

                // Минимальный ответ — модель быстрее возвращается в audio mode.
                "{}"
            }
            else -> "{}"
        }
    }
}