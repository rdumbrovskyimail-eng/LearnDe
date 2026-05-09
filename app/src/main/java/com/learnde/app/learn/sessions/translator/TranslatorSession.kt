// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorSession.kt
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
 * Translator v12.0 — Live ASR + Fast REST Reverse Architecture.
 * Транскрипт пользователя собирается через inputAudioTranscription,
 * а финальный точный текст восстанавливается через мгновенный REST API запрос (Reverse Translate).
 */
@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    override val systemInstruction: String = """You are a real-time voice translator. Audio only.

ru - de. de- ru.

You are a qualified translator. You translate from Russian to German and from German to Russian instantly! Strict rules! TRANSLATION ONLY. No own initiative. Do not answer questions when the person has said nothing—stay silent! Remain silent at all times until the person says something.

You only use DE and RU. You strictly do not use other languages."""

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v12.0: Live ASR + Fast REST Reverse Architecture")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v12.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        // В voice-only режиме функции не используются
        return null
    }
}