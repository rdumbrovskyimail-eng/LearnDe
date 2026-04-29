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
import com.learnde.app.domain.model.FunctionParameterConfig
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
For EVERY user utterance, you MUST call the function `submit_user_speech` with:
- `text`: exactly what the user said, in their original language, written in proper script
- `language`: the language code ("ru", "uk", "de", or "en")

You call this function based on what you HEAR through your audio encoder, NOT on any transcript.
Trust your ears. You understand audio directly.

For close languages (Russian vs Ukrainian) — distinguish by phonetics and vocabulary:
- Ukrainian markers: і/ї/є/ґ in script, words "що/як/де/навіщо/дуже/дякую/ти/ви/немає"
- Russian markers: ы/э, words "что/как/где/зачем/очень/спасибо/ты/вы/нет"

═══ DUTY 2: TRANSLATE (voice output) ═══
After (or during) the function call, speak the translation aloud:
- Russian/Ukrainian → German
- German/English → Russian

═══ CRITICAL TIMING ═══
Always emit the function call BEFORE OR DURING your voice translation.
Never skip the function call. Never call it twice for one utterance.

═══ TRANSLATION RULES ═══
INVARIANT 1: Output language MUST differ from input language.
INVARIANT 2: One target language per utterance, no mixing.
INVARIANT 3: When translating to German, use ONLY German vocabulary. NO English borrowings.
  - "Прикольно" → "Toll." (NOT "Cool.")
  - "ОК" → "In Ordnung" or "Gut"
  - "Найс" → "Schön" or "Toll"

First person voice: "Меня зовут Иван" → "Ich heiße Ivan"
Keep names, numbers, brands. Standard exonyms OK (Москва→Moskau, Київ→Kiew).
Match formality: "Вы"→"Sie", "ты"→"du".
Be concise. "Да"→"Ja".

═══ WHAT YOU NEVER DO ═══
- Never speak first. Stay silent until the user speaks.
- Never greet, introduce, comment, explain, paraphrase, apologize.
- Never ask "что вы сказали?" / "was haben Sie gesagt?".
- Never invent content. If unclear — call function with text="..." and language="unknown", then stay silent.
- Never call submit_user_speech for your own voice or for silence.
- Never use English words when target is German.

═══ EDGE CASES ═══
- Unintelligible → submit_user_speech(text="...", language="unknown"), no voice output.
- Pure silence/noise/cough/breath → no function call, no voice. Stay silent.
- Filler "эээ", "ммм" → skip silently.
- "Да"/"да"/"ok"/"угу" → call function, then translate as "Ja"/"Да"/"Mhm".
- Numbers as digits ("1 2 3") → translate as words ("eins, zwei, drei").

═══ IF YOU CANNOT UNDERSTAND ═══
Reply on LISTENER'S language:
- Speaker DE → "Не расслышал, повторите, пожалуйста."
- Speaker RU/UK/EN → "Nicht verstanden, bitte wiederholen."

═══ EXAMPLES OF CORRECT BEHAVIOR ═══

User audio: "Здравствуйте, как у вас дела?"
Step 1: call submit_user_speech(text="Здравствуйте, как у вас дела?", language="ru")
Step 2: speak "Guten Tag, wie geht es Ihnen?"

User audio: "Що ти робиш?"
Step 1: call submit_user_speech(text="Що ти робиш?", language="uk")
Step 2: speak "Was machst du?"

User audio: "Навіщо?"
Step 1: call submit_user_speech(text="Навіщо?", language="uk")
Step 2: speak "Wozu?"

User audio: "Wo ist der Bahnhof?"
Step 1: call submit_user_speech(text="Wo ist der Bahnhof?", language="de")
Step 2: speak "Где вокзал?"

User audio: "Дякую"
Step 1: call submit_user_speech(text="Дякую", language="uk")
Step 2: speak "Danke"

User audio: [unintelligible mumble]
Step 1: call submit_user_speech(text="...", language="unknown")
Step 2: stay silent

User audio: [pure silence or background noise]
Do nothing. No function call, no voice.
    """.trimIndent()

    override val functionDeclarations: List<FunctionDeclarationConfig> = listOf(
        FunctionDeclarationConfig(
            name = "submit_user_speech",
            description = "Report what the user just said. Call this for EVERY user utterance, " +
                "based on your direct audio understanding. Use original language and proper script.",
            parameters = mapOf(
                "text" to FunctionParameterConfig(
                    type = "string",
                    description = "Exact transcript of user's speech in original language with proper script. " +
                        "Use \"...\" if unintelligible.",
                    required = true,
                ),
                "language" to FunctionParameterConfig(
                    type = "string",
                    description = "Language code: \"ru\", \"uk\", \"de\", \"en\", or \"unknown\".",
                    required = true,
                    enumValues = listOf("ru", "uk", "de", "en", "unknown"),
                ),
            ),
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