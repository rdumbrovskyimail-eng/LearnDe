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
        logger.d("TranslatorSession v8.1: onEnter (pure audio, no functions)")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v8.1: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        logger.w("TranslatorSession v8.1: unexpected tool call ${call.name}")
        return null
    }
}