package com.learnde.app.learn.tutor

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TutorHintClient @Inject constructor(
    private val logger: AppLogger,
) {

    companion object {
        private const val BASE =
            "https://generativelanguage.googleapis.com/v1beta/models"
        private const val JSON_MIME = "application/json; charset=utf-8"

        /** Кол-во повторов при 429/5xx. */
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1200L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Запросить одну карточку.
     *
     * @param apiKey  отдельный ключ информирующей модели
     * @param model   id модели (по умолчанию gemini-2.5-flash-lite)
     * @param prompt  пользовательский промт (контекст события урока)
     * @return карточка или null (любая ошибка деградирует молча — урок важнее)
     */
    suspend fun fetchHint(
        apiKey: String,
        model: String,
        prompt: String,
    ): TutorHintResponse? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        var attempt = 0
        while (true) {
            val result = executeOnce(apiKey, model, prompt)
            when {
                result.success != null -> return@withContext result.success
                !result.retryable || attempt >= MAX_RETRIES -> return@withContext null
                else -> {
                    attempt++
                    logger.d("TutorHint: retry $attempt/${MAX_RETRIES} after ${result.error}")
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private data class Attempt(
        val success: TutorHintResponse? = null,
        val retryable: Boolean = false,
        val error: String? = null,
    )

    private suspend fun executeOnce(
        apiKey: String,
        model: String,
        prompt: String,
    ): Attempt {
        val body = buildRequestBody(prompt).toString()
        val request = Request.Builder()
            .url("$BASE/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MIME.toMediaType()))
            .build()

        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            return Attempt(retryable = true, error = "io: ${e.message}")
        }

        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val retryable = resp.code == 429 || resp.code >= 500
                logger.w("TutorHint: HTTP ${resp.code} ${raw.take(200)}")
                return Attempt(retryable = retryable, error = "http ${resp.code}")
            }
            return try {
                Attempt(success = parseResponse(raw))
            } catch (e: Exception) {
                logger.w("TutorHint: parse failed: ${e.message}")
                Attempt(retryable = false, error = "parse")
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  REQUEST
    // ────────────────────────────────────────────────────────

    private fun buildRequestBody(prompt: String) = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", SYSTEM_PROMPT) })
            })
        })
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", prompt) })
                })
            })
        })
        put("generationConfig", buildJsonObject {
            put("temperature", 0.4)
            put("maxOutputTokens", 512)
            put("responseMimeType", "application/json")
            // flash-lite: thinkingBudget=0 → мгновенный ответ
            put("thinkingConfig", buildJsonObject {
                put("thinkingBudget", 0)
            })
        })
    }

    private val SYSTEM_PROMPT = """
        Ты — методист немецкого языка для русскоязычных учеников уровня A1.
        Твоя задача — короткие ИНФОРМИРУЮЩИЕ карточки, которые показываются
        ученику параллельно с голосовым уроком.

        ЖЁСТКИЕ ПРАВИЛА:
        1. Ответ — ТОЛЬКО валидный JSON ровно такой структуры:
           {"title": "...", "body": "...", "examples": [{"de": "...", "ru": "..."}]}
        2. title — до 40 символов, по-русски.
        3. body — 1–3 коротких предложения по-русски. Никакой воды.
        4. examples — 2–4 примера. de — корректный немецкий уровня A1,
           ru — точный перевод. Если примеры не нужны — пустой массив.
        5. Лексика примеров — только A1. Никаких слов выше уровня.
        6. Никакого markdown, никаких пояснений вне JSON.
    """.trimIndent()

    // ────────────────────────────────────────────────────────
    //  RESPONSE
    // ────────────────────────────────────────────────────────

    private fun parseResponse(raw: String): TutorHintResponse? {
        val root = json.parseToJsonElement(raw).jsonObject
        val text = root["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: return null

        // responseMimeType=application/json гарантирует чистый JSON,
        // но на всякий случай срезаем возможные ограждения.
        val clean = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        return json.decodeFromString(TutorHintResponse.serializer(), clean)
    }

    // ────────────────────────────────────────────────────────
    //  OkHttp → coroutines
    // ────────────────────────────────────────────────────────

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWith(Result.failure(e))
                }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }
}