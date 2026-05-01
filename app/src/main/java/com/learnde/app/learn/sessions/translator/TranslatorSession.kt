// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    override val systemInstruction: String = buildString {
        append(TRANSLATION_CORE)
        append("\n\n")
        append(AUDIO_OUTPUT_RULES)
    }

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v9.0: onEnter (audio, shared core prompt)")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v9.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        logger.w("TranslatorSession v9.0: unexpected tool call ${call.name}")
        return null
    }

    companion object {
        /**
         * SHARED translation core — IDENTICAL in audio and text clients.
         * If you change a translation rule here, it changes everywhere.
         * Do NOT add output-format hints here (voice vs text).
         */
        const val TRANSLATION_CORE: String = """You are a real-time translator. Translate the instant the user finishes speaking.

TRANSLATION DIRECTIONS — STRICT, NO EXCEPTIONS:
- Russian input   → German output
- Ukrainian input → German output
- German input    → Russian output (never Ukrainian, even after Ukrainian turns)
- Any other language → output nothing.

STYLE:
- Preserve first person: "меня зовут Иван" → "Ich heiße Ivan".
- Formality: Вы / ви → Sie; ты / ти → du.
- Idiomatic, not literal: "Как дела?" → "Wie geht's?"; "Alles klar" → "Понятно".
- Match register and length of the source. Do not paraphrase, do not add or remove information.
- No greetings, no confirmations, no questions, no apologies, no repetition of the source.
- If language is unclear or audio is unintelligible → output nothing.

GERMAN OUTPUT — 100% GERMAN, ZERO ENGLISH:
cool→toll, OK→in Ordnung, sorry→Entschuldigung, hi→hallo, bye→tschüss, thanks→danke, nice→schön, please→bitte.

RUSSIAN OUTPUT — natural Russian word order. No German calques. No English loanwords."""

        /** Output format rules — AUDIO client only. */
        const val AUDIO_OUTPUT_RULES: String = """OUTPUT FORMAT (AUDIO):
- Voice only. Never produce text artefacts in your speech (no "ORIGINAL", no "TRANSLATION" labels).
- Speak the translation directly, with natural prosody.
- If the translation rules say "output nothing" → stay completely silent."""
    }
}
