// ═══════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/learnde/app/data/vosk/VoskTranscriber.kt
//
// Параллельный offline-транскриптор на двух моделях (RU + DE).
//
// Слушает два независимых PCM-потока:
//  • micPcm16k    — 16 kHz, моно, 16-bit LE (микрофон, твой голос)
//  • playbackPcm24k — 24 kHz, моно, 16-bit LE (ответ Gemini, ресэмпл → 16)
//
// На каждом потоке работают 2 recognizer'а (RU и DE) параллельно.
// Когда один из них выдаёт partial/final — выбираем язык по confidence
// и эмитим в events.
//
// Жизненный цикл:
//   start(scope, micFlow, playbackFlow) — стартует обе подписки
//   stop()                              — закрывает всё
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.vosk

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskTranscriber @Inject constructor(
    private val modelLoader: VoskModelLoader,
    private val logger: AppLogger,
) {

    companion object {
        private const val SAMPLE_RATE = 16_000f

        // Минимальная длина partial текста чтобы вообще его эмитить
        // (отсеивает шум вроде одной буквы)
        private const val MIN_PARTIAL_LEN = 2

        // Минимальный отрыв средней confidence одного языка от другого,
        // чтобы признать его победителем. Иначе считаем UNKNOWN и игнорим.
        private const val LANG_CONFIDENCE_MARGIN = 0.05
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _events = MutableSharedFlow<VoskEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<VoskEvent> = _events.asSharedFlow()

    private var micJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile private var running = false

    // Дедуп partial'ов: эмитим только когда текст изменился
    @Volatile private var lastMicPartial: String = ""
    @Volatile private var lastPlaybackPartial: String = ""

    // Ссылки на playback-recognizer'ы для форсированного finalize
    @Volatile private var recPbRuRef: Recognizer? = null
    @Volatile private var recPbDeRef: Recognizer? = null

    /**
     * Стартует транскрайбер. Должен быть вызван ОДИН раз при старте
     * translator-сессии. Модели должны быть уже загружены через
     * modelLoader.loadModels() — иначе будет ошибка.
     *
     * @param scope CoroutineScope в котором будут жить корутины (обычно viewModelScope)
     * @param micFlow PCM 16 kHz моно с микрофона (audioEngine.micOutput)
     * @param playbackFlow PCM 24 kHz моно от Gemini (audioEngine.playbackSync)
     */
    suspend fun start(
        scope: CoroutineScope,
        micFlow: Flow<ByteArray>,
        playbackFlow: Flow<ByteArray>,
    ) {
        if (running) {
            logger.d("VoskTranscriber: already running, skipping start")
            return
        }
        running = true

        val (modelRu, modelDe) = modelLoader.loadModels()

        // ───── MIC поток (16 kHz, без ресэмпла) ─────
        val recMicRu = Recognizer(modelRu, SAMPLE_RATE)
        val recMicDe = Recognizer(modelDe, SAMPLE_RATE)
        // Включаем confidence для выбора языка
        runCatching { recMicRu.setWords(true) }
        runCatching { recMicDe.setWords(true) }

        micJob = scope.launch {
            try {
                logger.d("VoskTranscriber: MIC channel started")
                micFlow.collect { chunk ->
                    if (!running) return@collect
                    processChunk(
                        pcm = chunk,
                        recRu = recMicRu,
                        recDe = recMicDe,
                        source = VoskSource.MIC,
                    )
                }
            } finally {
                runCatching { recMicRu.close() }
                runCatching { recMicDe.close() }
                logger.d("VoskTranscriber: MIC channel closed")
            }
        }

        // ───── PLAYBACK поток (24 kHz, ресэмпл в 16 kHz) ─────
        val recPbRu = Recognizer(modelRu, SAMPLE_RATE)
        val recPbDe = Recognizer(modelDe, SAMPLE_RATE)
        runCatching { recPbRu.setWords(true) }
        runCatching { recPbDe.setWords(true) }
        recPbRuRef = recPbRu
        recPbDeRef = recPbDe

        playbackJob = scope.launch {
            try {
                logger.d("VoskTranscriber: PLAYBACK channel started")
                playbackFlow.collect { chunk24k ->
                    if (!running) return@collect
                    val chunk16k = downsample24kTo16k(chunk24k)
                    processChunk(
                        pcm = chunk16k,
                        recRu = recPbRu,
                        recDe = recPbDe,
                        source = VoskSource.PLAYBACK,
                    )
                }
            } finally {
                recPbRuRef = null
                recPbDeRef = null
                runCatching { recPbRu.close() }
                runCatching { recPbDe.close() }
                logger.d("VoskTranscriber: PLAYBACK channel closed")
            }
        }

        logger.d("VoskTranscriber: ✓ started")
    }

    fun stop() {
        if (!running) return
        running = false
        micJob?.cancel()
        playbackJob?.cancel()
        micJob = null
        playbackJob = null
        lastMicPartial = ""
        lastPlaybackPartial = ""
        logger.d("VoskTranscriber: stopped")
    }

    /**
     * Форсированно финализирует playback-recognizer'ы. Вызывать когда
     * Gemini Live прислал TurnComplete — Vosk не услышит "тишину" сам,
     * потому что просто перестаёт получать чанки.
     *
     * Без этого partial накапливается между фразами Gemini и склеивается
     * с следующим ответом.
     */
    fun finalizePlayback() {
        recPbRuRef?.let { rec ->
            runCatching {
                val finalJson = rec.finalResult
                val text = extractText(finalJson)
                if (text.isNotBlank()) {
                    val ruText = text
                    val deText = extractText(recPbDeRef?.finalResult)
                    val (chosen, lang) = if (deText.length > ruText.length)
                        deText to VoskLang.DE
                    else
                        ruText to VoskLang.RU
                    if (chosen.isNotBlank()) {
                        _events.tryEmit(VoskEvent.Final(VoskSource.PLAYBACK, chosen, lang))
                    }
                }
            }
        }
        recPbDeRef?.let { runCatching { it.finalResult } }
        lastPlaybackPartial = ""
    }

    // ════════════════════════════════════════════════════════════
    //  PRIVATE
    // ════════════════════════════════════════════════════════════

    /**
     * Прогоняет один PCM-чанк через обе модели, эмитит события.
     */
    private suspend fun processChunk(
        pcm: ByteArray,
        recRu: Recognizer,
        recDe: Recognizer,
        source: VoskSource,
    ) {
        // Скармливаем чанк в оба recognizer-а
        val finalRu = runCatching { recRu.acceptWaveForm(pcm, pcm.size) }.getOrDefault(false)
        val finalDe = runCatching { recDe.acceptWaveForm(pcm, pcm.size) }.getOrDefault(false)

        // Если хотя бы один Recognizer говорит "это конец фразы" — эмитим Final.
        // Берём текст того у кого выше средний confidence.
        if (finalRu || finalDe) {
            val ruResult = runCatching { recRu.result }.getOrNull()
            val deResult = runCatching { recDe.result }.getOrNull()

            val (text, lang) = pickByConfidence(ruResult, deResult)
            if (text.isNotBlank()) {
                _events.tryEmit(VoskEvent.Final(source, text, lang))
            }
            return
        }

        // Иначе обновляем partial — эмитим только если изменился по сравнению
        // с прошлым chunk'ом этого же recognizer'а.
        val partialRuJson = runCatching { recRu.partialResult }.getOrNull()
        val partialDeJson = runCatching { recDe.partialResult }.getOrNull()
        val (partialText, partialLang) = pickPartialByLength(partialRuJson, partialDeJson)

        // Фильтр 1: минимальная длина (отсекает одиночные буквы)
        if (partialText.length < MIN_PARTIAL_LEN) return

        // Фильтр 2: дедуп — не эмитим повторяющийся текст
        when (source) {
            VoskSource.MIC -> {
                if (partialText == lastMicPartial) return
                lastMicPartial = partialText
            }
            VoskSource.PLAYBACK -> {
                if (partialText == lastPlaybackPartial) return
                lastPlaybackPartial = partialText
            }
        }

        _events.tryEmit(VoskEvent.Partial(source, partialText, partialLang))
    }

    /**
     * Выбираем результат с большей средней confidence.
     * Vosk возвращает JSON: {"result":[{"conf":0.95,"word":"..."},...], "text":"..."}
     */
    private fun pickByConfidence(ruJson: String?, deJson: String?): Pair<String, VoskLang> {
        val ruText = extractText(ruJson)
        val deText = extractText(deJson)
        val ruConf = extractAvgConfidence(ruJson)
        val deConf = extractAvgConfidence(deJson)

        return when {
            ruText.isBlank() && deText.isBlank() -> "" to VoskLang.UNKNOWN
            ruText.isBlank() -> deText to VoskLang.DE
            deText.isBlank() -> ruText to VoskLang.RU
            ruConf > deConf + LANG_CONFIDENCE_MARGIN -> ruText to VoskLang.RU
            deConf > ruConf + LANG_CONFIDENCE_MARGIN -> deText to VoskLang.DE
            // confidence близка — берём более длинный (обычно "правильный" язык даёт связный текст)
            ruText.length >= deText.length -> ruText to VoskLang.RU
            else -> deText to VoskLang.DE
        }
    }

    /**
     * Для partial confidence ещё не доступна (Vosk возвращает только
     * текст без conf). Берём более длинный — он обычно правильный.
     */
    private fun pickPartialByLength(ruJson: String?, deJson: String?): Pair<String, VoskLang> {
        val ruText = extractPartial(ruJson)
        val deText = extractPartial(deJson)
        return when {
            ruText.isBlank() && deText.isBlank() -> "" to VoskLang.UNKNOWN
            ruText.length > deText.length -> ruText to VoskLang.RU
            deText.length > ruText.length -> deText to VoskLang.DE
            ruText.isNotBlank() -> ruText to VoskLang.RU
            else -> deText to VoskLang.DE
        }
    }

    private fun extractText(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return runCatching {
            json.parseToJsonElement(jsonStr).jsonObject["text"]
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun extractPartial(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return runCatching {
            json.parseToJsonElement(jsonStr).jsonObject["partial"]
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun extractAvgConfidence(jsonStr: String?): Double {
        if (jsonStr.isNullOrBlank()) return 0.0
        return runCatching {
            val arr = json.parseToJsonElement(jsonStr).jsonObject["result"]
                ?.jsonArray ?: return@runCatching 0.0
            if (arr.isEmpty()) return@runCatching 0.0
            val sum = arr.sumOf { el: JsonElement ->
                el.jsonObject["conf"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            }
            sum / arr.size
        }.getOrDefault(0.0)
    }

    /**
     * Простой ресэмплер 24 kHz → 16 kHz для PCM 16-bit LE моно.
     * Декрементальный фактор = 2/3, то есть берём 2 семпла из каждых 3.
     *
     * Качество не аудиофильское, но для ASR-распознавания ответа Gemini
     * этого хватает с запасом.
     */
    private fun downsample24kTo16k(input24k: ByteArray): ByteArray {
        // 24000 / 16000 = 3/2 — берём 2 семпла из каждых 3
        val sampleCount24k = input24k.size / 2
        val sampleCount16k = (sampleCount24k * 2) / 3
        val output = ByteArray(sampleCount16k * 2)

        var inIdx = 0
        var outIdx = 0
        var counter = 0

        while (inIdx + 1 < input24k.size && outIdx + 1 < output.size) {
            // Пропускаем каждый 3-й семпл
            if (counter % 3 != 2) {
                output[outIdx] = input24k[inIdx]
                output[outIdx + 1] = input24k[inIdx + 1]
                outIdx += 2
            }
            inIdx += 2
            counter++
        }
        return output
    }
}