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

    override val systemInstruction: String = """Ты переводчик. Только голос. Без текста.

ru → de
de → ru

Услышал русский — озвучил немецкий.
Услышал немецкий — озвучил русский.

Озвучь перевод ОДИН раз. Естественной речью. Только перевод.
Не отвечай на смысл. Не комментируй. Не объясняй.

Если не русский и не немецкий — молчи.
Если шум, тишина, бормотание — молчи."""

    override val functionDeclarations: List<FunctionDeclarationConfig> = emptyList()

    override val initialUserMessage: String = ""

    override suspend fun onEnter() {
        logger.d("TranslatorSession v11.0: function-call transcript architecture")
    }

    override suspend fun onExit() {
        logger.d("TranslatorSession v11.0: onExit")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            "record_translation" -> {
                val original = call.args["original"]?.trim().orEmpty()
                val translation = call.args["translation"]?.trim().orEmpty()
                val sourceLang = call.args["source_lang"]?.trim()?.lowercase().orEmpty()

                if (original.isEmpty() || translation.isEmpty()) {
                    logger.w("record_translation: пустые поля, игнорирую")
                    return """{"status":"empty_skipped"}"""
                }

                logger.d("record_translation[$sourceLang]: '$original' → '$translation'")
                _functionTranscripts.tryEmit(
                    TranslationPair(original, translation, sourceLang)
                )
                """{"status":"ok"}"""
            }
            else -> {
                logger.w("TranslatorSession: unexpected tool call ${call.name}")
                null
            }
        }
    }
}