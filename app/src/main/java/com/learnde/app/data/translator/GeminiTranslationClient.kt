// ═══════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/learnde/app/data/translator/GeminiTranslationClient.kt
//
// Быстрый REST-клиент для транскрипции + перевода аудио.
//
// Использует gemini-3-flash-preview через streamGenerateContent.
// Отправляет PCM 16kHz WAV inline base64 + промпт.
// Возвращает structured JSON {"original": "...", "translation": "..."}.
//
// Latency: первый chunk ~300мс, полный ответ ~600-1000мс на короткую фразу.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.translator

import android.util.Base64
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.experimental.and

@Singleton
class GeminiTranslationClient @Inject constructor(
    private val logger: AppLogger,
) {

    companion object {
        // Самая быстрая модель Gemini 3 series для коротких задач
        private const val MODEL = "gemini-3-flash-preview"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com" +
            "/v1beta/models/$MODEL:streamGenerateContent"

        // Стрим возвращает chunks с alt=sse (SSE стандарт). Без alt — JSON-array.
        // Мы используем JSON-array режим: ?key=...&alt=json — тогда ответ это
        // массив events, разделяемых "\r\n\r\n". Парсим как поток.
        // streamGenerateContent без alt - возвращает JSON Array of GenerateContentResponse
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // OkHttp с короткими таймаутами — мы шлём короткое аудио
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Транскрибирует и переводит аудио-фразу.
     *
     * @param pcm16kBytes PCM 16-bit LE 16kHz моно — то что шлём в Gemini Live
     * @param apiKey API ключ
     * @return TranslationResult с original/translation либо ошибка через exception
     */
    suspend fun translate(
        pcm16kBytes: ByteArray,
        apiKey: String,
    ): TranslationResult = withContext(Dispatchers.IO) {

        if (pcm16kBytes.isEmpty()) {
            return@withContext TranslationResult("", "")
        }

        // 1. Заворачиваем PCM в WAV — Gemini принимает PCM только в WAV-контейнере
        val wavBytes = pcmToWav(pcm16kBytes, sampleRate = 16_000)
        val wavBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        // 2. Тело запроса: inline_data с аудио + текстовый промпт
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "audio/wav")
                                put("data", wavBase64)
                            })
                        })
                        add(buildJsonObject {
                            put("text", PROMPT)
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.0)
                put("topP", 0.95)
                put("maxOutputTokens", 256)
                put("responseMimeType", "application/json")
                // Убираем thinking — для короткой задачи не нужен, замедляет.
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", "minimal")
                })
            })
        }

        val rawBody = body.toString()
        val startedAt = System.currentTimeMillis()
        logger.d("GeminiTranslate → POST (${rawBody.length} chars, audio ${pcm16kBytes.size}B)")

        // 3. POST на streamGenerateContent (он быстрее обычного generateContent
        //    потому что начинает отдавать данные сразу как только модель сгенерила
        //    первые токены). Для нашей задачи — JSON в одной фразе — выигрыш ~30-40%.
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey&alt=sse")
            .post(rawBody.toRequestBody("application/json".toMediaType()))
            .build()

        val responseBuilder = StringBuilder()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(500)
                    logger.e("GeminiTranslate ← HTTP ${resp.code}: $errBody")
                    throw IllegalStateException("Gemini REST ${resp.code}: $errBody")
                }

                // SSE stream: каждый event = "data: <json>\n\n"
                val reader = resp.body?.byteStream()?.bufferedReader()
                    ?: throw IllegalStateException("Empty response body")

                reader.useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.substringAfter("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue

                        // Извлекаем text из chunk'а и аккумулируем
                        val chunkText = extractTextFromChunk(payload)
                        if (chunkText.isNotEmpty()) {
                            responseBuilder.append(chunkText)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            logger.e("GeminiTranslate ✗ failed after ${elapsed}ms: ${e.message}")
            throw e
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val fullJson = responseBuilder.toString().trim()
        logger.d("GeminiTranslate ← ${elapsed}ms, ${fullJson.length} chars")

        // 4. Парсим финальный JSON {"original": "...", "translation": "..."}
        return@withContext parseFinalJson(fullJson)
    }

    /** Извлекает .candidates[0].content.parts[*].text из одного chunk'а */
    private fun extractTextFromChunk(chunkJson: String): String {
        return runCatching {
            val root = json.parseToJsonElement(chunkJson).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return@runCatching ""
            if (candidates.isEmpty()) return@runCatching ""
            val parts = candidates[0].jsonObject["content"]?.jsonObject
                ?.get("parts")?.jsonArray ?: return@runCatching ""
            buildString {
                for (part in parts) {
                    val txt = part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    if (!txt.isNullOrEmpty()) append(txt)
                }
            }
        }.getOrDefault("")
    }

    /** Парсит финальный аккумулированный JSON ответа от модели. */
    private fun parseFinalJson(fullText: String): TranslationResult {
        if (fullText.isBlank()) return TranslationResult("", "")
        return runCatching {
            val obj = json.parseToJsonElement(fullText).jsonObject
            val orig = obj["original"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val trans = obj["translation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            TranslationResult(orig, trans)
        }.getOrElse { e ->
            logger.w("GeminiTranslate: failed to parse final JSON: ${e.message} | raw='$fullText'")
            TranslationResult("", "")
        }
    }

    /**
     * Заворачивает raw PCM 16-bit LE в WAV-контейнер.
     * Gemini принимает audio/wav inline.
     */
    private fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcmBytes.size)
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono = 2 bytes per sample

        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToLeBytes(totalDataLen))
        out.write("WAVE".toByteArray())

        // fmt subchunk
        out.write("fmt ".toByteArray())
        out.write(intToLeBytes(16))               // subchunk1 size
        out.write(shortToLeBytes(1))              // PCM = 1
        out.write(shortToLeBytes(1))              // numChannels = 1
        out.write(intToLeBytes(sampleRate))
        out.write(intToLeBytes(byteRate))
        out.write(shortToLeBytes(2))              // blockAlign
        out.write(shortToLeBytes(16))             // bitsPerSample

        // data subchunk
        out.write("data".toByteArray())
        out.write(intToLeBytes(pcmBytes.size))
        out.write(pcmBytes)
        return out.toByteArray()
    }

    private fun intToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 24) and 0xff).toByte(),
    )

    private fun shortToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
    )

    private val PROMPT = """You are a real-time bilingual transcription engine.

The user spoke in Russian or German. Your task:
1. Transcribe what they said in the original language, exactly as spoken.
2. Translate: ru → de, or de → ru.

Return ONLY valid JSON in this exact format:
{"original": "exact transcribed text in original language", "translation": "translation in the other language"}

Strict rules:
- ONLY Russian or German. No English, no other languages.
- Empty/silent/unclear audio → {"original": "", "translation": ""}
- No comments, no explanations, no extra fields. Pure JSON only.""".trimIndent()
}

data class TranslationResult(
    val original: String,
    val translation: String,
)