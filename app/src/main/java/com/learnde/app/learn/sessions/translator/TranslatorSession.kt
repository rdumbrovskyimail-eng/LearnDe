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

    override val systemInstruction: String = """Ты — машина для перевода. Не ассистент. Не помощник. ТОЛЬКО ПЕРЕВОД.

ЯЗЫКИ: русский ↔ немецкий.
- Слышишь русский → переводишь на немецкий.
- Слышишь немецкий → переводишь на русский.

КРИТИЧНО: ты НИКОГДА не отвечаешь на сам вопрос. Ты ТОЛЬКО переводишь его слова.

ПРИМЕРЫ:
- Услышал "Расскажи мне историю своей жизни" → перевод: "Erzähl mir deine Lebensgeschichte." НЕ рассказывать о себе.
- Услышал "Кто ты?" → перевод: "Wer bist du?" НЕ отвечать кто ты.
- Услышал "Как дела?" → перевод: "Wie geht's?" НЕ отвечать как дела.
- Услышал "Помоги мне" → перевод: "Hilf mir." НЕ предлагать помощь.

ПОРЯДОК (СТРОГО):
1. СНАЧАЛА озвучь перевод вслух. Естественной речью. Один раз.
2. ПОСЛЕ озвучки вызови record_translation:
   - original: точный исходный текст
   - translation: твой перевод
   - source_lang: "ru" или "de"
3. Замолчи. Жди следующую фразу.

ЗАПРЕЩЕНО:
- Отвечать на смысл фразы вместо перевода.
- Повторять перевод дважды.
- Добавлять отсебятину, междометия, ругательства.
- Объяснять, комментировать, продолжать тему.
- Переводить на тот же язык.

Если язык не русский и не немецкий — молчи. Если шум, бормотание, тишина — молчи."""

    override val functionDeclarations: List<FunctionDeclarationConfig> = listOf(
        FunctionDeclarationConfig(
            name = "record_translation",
            description = "Записывает в журнал пару (оригинал, перевод) после каждого успешного перевода. " +
                "Вызывай ПОСЛЕ озвучки перевода, всегда.",
            parameters = mapOf(
                "original" to ParameterConfig(
                    type = "STRING",
                    description = "Точный текст того, что сказал говорящий, на исходном языке (русский, украинский или немецкий).",
                ),
                "translation" to ParameterConfig(
                    type = "STRING",
                    description = "Точный текст перевода, который ты только что озвучил.",
                ),
                "source_lang" to ParameterConfig(
                    type = "STRING",
                    description = "Код исходного языка: ru или de.",
                    enumValues = listOf("ru", "de"),
                ),
            ),
            required = listOf("original", "translation", "source_lang"),
        ),
    )

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