// Путь: app/src/main/java/com/learnde/app/data/translator/GeminiTranslationClient.kt
package com.learnde.app.data.translator

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiTranslationClient @Inject constructor(
    private val logger: AppLogger,
) {
    companion object {
        // Легкая и ультрабыстрая модель
        private const val MODEL = "gemini-2.5-flash-lite"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Жесткие короткие таймауты: модель-лайт отвечает за миллисекунды!
    // Настраиваем ConnectionPool для повторного использования соединений (убирает 100-200мс на TLS-handshake)
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    suspend fun reverseTranslate(textFromSocket: String, apiKey: String): ReverseResult = withContext(Dispatchers.IO) {
        if (textFromSocket.isBlank()) return@withContext ReverseResult("", "")

        val reqStart = System.currentTimeMillis()
        // Ультра-короткий промпт для минимизации задержки TTFT (Time To First Token)
        val sysPrompt = "DE→RU or RU→DE. Output translation only."

        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", sysPrompt) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", textFromSocket) }) }) })
            })
            put("generationConfig", buildJsonObject { put("temperature", 0.0) })
        }

        val request = Request.Builder().url("$ENDPOINT?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()

        suspend fun executeOnce(): String? = withContext(Dispatchers.IO) {
            runCatching {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val responseText = resp.body?.string() ?: return@runCatching null
                    json.parseToJsonElement(responseText)
                        .jsonObject["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")?.jsonObject?.get("parts")
                        ?.jsonArray?.firstOrNull()?.jsonObject?.get("text")
                        ?.jsonPrimitive?.content?.trim()
                }
            }.getOrNull()
        }

        var resultText = executeOnce()
        if (resultText.isNullOrEmpty()) {
            logger.w("ReverseTr retry…")
            resultText = executeOnce()
        }

        val time = System.currentTimeMillis() - reqStart
        if (resultText.isNullOrEmpty()) {
            logger.e("ReverseTr ✗ both attempts failed (${time}ms)")
            return@withContext ReverseResult("", "")
        }

        logger.d("ReverseTr ✓ (${time}ms): [$textFromSocket] -> [$resultText]")
        val isCyrl = resultText.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        ReverseResult(
            reconstructedText = resultText,
            lang = if (isCyrl) "RU" else "DE"
        )
    }

    suspend fun warmUp(apiKey: String) = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext
        runCatching {
            val body = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", "ok") }) })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.0)
                    put("maxOutputTokens", 1)
                })
            }
            val request = Request.Builder().url("$ENDPOINT?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().close()
            logger.d("REST warmup ✓")
        }.onFailure { logger.d("REST warmup skipped: ${it.message}") }
    }
}

data class ReverseResult(
    val reconstructedText: String,
    val lang: String
)