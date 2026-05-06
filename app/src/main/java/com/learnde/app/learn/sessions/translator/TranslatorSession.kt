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
 * Translator v11.0 — function-call архитектура.
 *
 * Транскрипт приходит через function call `record_translation` —
 * модель сама пишет точный текст оригинала и перевода ПОСЛЕ озвучки.
 * Это даёт высшую точность транскрипта (модель пишет осознанно,
 * а не спекулятивно через ASR-слой).
 *
 * Fallback: outputAudioTranscription включён на случай если модель
 * пропустит вызов функции — UI не останется пустым.
 */
@Singleton
class TranslatorSession @Inject constructor(
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "translator"

    /**
     * События транскрипта, прилетающие через function call.
     * Их слушает LearnCoreViewModel и пишет в transcript.
     */
    private val _functionTranscripts = MutableSharedFlow<TranslationPair>(
        replay = 0, extraBufferCapacity = 32,
    )
    val functionTranscripts: SharedFlow<TranslationPair> = _functionTranscripts.asSharedFlow()

    data class TranslationPair(
        val original: String,
        val translation: String,
        val sourceLang: String, // "ru" | "uk" | "de"
    )

    override val systemInstruction: String = """You are a real-time voice translator. Audio only.

ru - de. de- ru.

You are a qualified translator. You translate from Russian to German and from German to Russian instantly! Strict rules! TRANSLATION ONLY. No own initiative. Do not answer questions when the person has said nothing—stay silent! Remain silent at all times until the person says something.

You only use DE and RU. You strictly do not use other languages."""

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v11.0: function-call transcript architecture")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v11.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        // В voice-only режиме функции не используются
        return null
    }
}