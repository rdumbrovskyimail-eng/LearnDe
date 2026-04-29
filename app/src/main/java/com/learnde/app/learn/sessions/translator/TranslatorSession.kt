// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v4.0
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
//
// ИЗМЕНЕНИЯ v4.0:
//   - Function calling для транскрипции речи пользователя
//   - ASR используется ТОЛЬКО для транскрипции голоса Gemini
//   - Транскрипцию пользователя даёт сама модель через её аудио-энкодер
//   - Параллельный вызов: функция + голосовой перевод одновременно
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

/**
 * Событие транскрипции пользователя, полученное от модели через function call.
 * Это НЕ ASR-результат. Это интерпретация модели, основанная на её аудио-понимании.
 */
data class UserSpeechEvent(
    val text: String,
    val language: String,    // "ru" | "uk" | "de" | "en"
    val timestamp: Long = System.currentTimeMillis(),
)

@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    // ════ Канал для UI: модель докладывает что услышала ════
    private val _userSpeechFlow = MutableSharedFlow<UserSpeechEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val userSpeechFlow: SharedFlow<UserSpeechEvent> = _userSpeechFlow.asSharedFlow()

    override val systemInstruction: String = """
You are a real-time voice translator with TWO simultaneous duties.

═══ DUTY 1: TRANSCRIBE USER SPEECH (function call) ═══
For EVERY user utterance, you MUST FIRST call the function submit_user_speech with:
  - text: exactly what the user said, in their original language, written in proper script
  - language: the language code ("ru", "uk", "de", or "en")

You call this function based on what you HEAR through your audio encoder. Trust your ears.

═══ DUTY 2: TRANSLATE (voice output) ═══
IMMEDIATELY after receiving the function response, you MUST use your VOICE to speak the translation aloud.
DO NOT end your turn without speaking. DO NOT just output text. You MUST generate audio.

  - Russian/Ukrainian → German
  - German/English → Russian

═══ CRITICAL TIMING ═══
Always emit the function call FIRST. Then wait for the response. Then IMMEDIATELY speak.

═══ TRANSLATION RULES ═══
INVARIANT 1: Output language MUST differ from input language.
INVARIANT 2: One target language per utterance, no mixing.
INVARIANT 3: When translating to German, use ONLY German vocabulary. NO English borrowings.
  - "Прикольно" → "Toll." (NOT "Cool.")
  - "ОК" → "In Ordnung" or "Gut"

First person voice: "Меня зовут Иван" → "Ich heiße Ivan". Match formality: "Вы"→"Sie", "ты"→"du". Be concise.

═══ WHAT YOU NEVER DO ═══
  - Never speak first. Stay silent until the user speaks.
  - Never greet, introduce, comment, explain, paraphrase, apologize.
  - Never ask "что вы сказали?".
  - Never invent content. If unclear — call function with text="..." and language="unknown", then stay silent.
  - Never call submit_user_speech for your own voice or for silence.
""".trimIndent()

    override val functionDeclarations: List<FunctionDeclarationConfig> = listOf(
        FunctionDeclarationConfig(
            name = "submit_user_speech",
            description = "Report what the user just said. Call this for EVERY user utterance, " +
                "based on your direct audio understanding. Use original language and proper script.",
            parameters = mapOf(
                "text" to ParameterConfig(
                    type = "STRING",
                    description = "Exact transcript of user's speech in original language with " +
                        "proper script. Use \"...\" if unintelligible.",
                ),
                "language" to ParameterConfig(
                    type = "STRING",
                    description = "Language code: one of \"ru\", \"uk\", \"de\", \"en\", \"unknown\". " +
                        "Use phonetic and lexical markers to distinguish Russian from Ukrainian.",
                ),
            ),
            required = listOf("text", "language"),
        ),
    )

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v4.0: onEnter (function-based user transcription)")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v4.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            "submit_user_speech" -> {
                val text = (call.args["text"] as? String)?.trim().orEmpty()
                val lang = (call.args["language"] as? String)?.trim()?.lowercase().orEmpty()

                if (text.isNotEmpty() && text != "...") {
                    logger.d("TranslatorSession: user speech [$lang]: $text")
                    _userSpeechFlow.tryEmit(
                        UserSpeechEvent(text = text, language = lang.ifEmpty { "unknown" })
                    )
                } else {
                    logger.d("TranslatorSession: skipping unintelligible/empty utterance")
                }

                // Возвращаем подтверждение модели — это критично для разблокировки её ответа
                """{"status":"ok"}"""
            }
            else -> {
                logger.w("TranslatorSession: unknown tool call: ${call.name}")
                """{"status":"unknown_function"}"""
            }
        }
    }
}