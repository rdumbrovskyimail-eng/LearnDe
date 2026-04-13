package com.codeextractor.app.domain.avatar.audio

import com.codeextractor.app.domain.avatar.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Объединённый DSP-анализатор v3.
 *
 * Источники:
 * - Claude: YIN-lite pitch, формантная нормализация, spectral flux
 * - Gemini: ZCR (Zero-Crossing Rate), isPlosive flag, rectified flux
 * - Grok: pitchVariance tracking для эмоциональной экспрессивности
 *
 * Все метрики вычисляются за ОДИН проход FFT (zero-alloc в hot loop).
 */
class AudioDSPAnalyzer(private val sampleRate: Int = 24000) {

    companion object {
        const val FFT_SIZE = 512
        private const val HALF = FFT_SIZE / 2
    }

    private val binRes = sampleRate.toFloat() / FFT_SIZE
    private val samples = FloatArray(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE)
    private val cosT = FloatArray(HALF)
    private val sinT = FloatArray(HALF)
    private val bitRev = IntArray(FFT_SIZE)
    private var samplePos = 0

    // ═══ Spectral flux (Gemini + Claude): разница между текущим и предыдущим спектром ═══
    private val prevMagnitudes = FloatArray(HALF)

    // ═══ Pitch variance (Grok): вариация F0 для эмоциональности ═══
    private val pitchHistory = FloatArray(12)
    private var pitchHistoryIdx = 0
    private var pitchHistoryFilled = 0

    // ═══ Adaptive baseline pitch (Gemini): адаптация к голосу спикера ═══
    private var baselinePitch = 0f
    private var baselineInitialized = false

    init { precompute() }

    /**
     * Обрабатывает PCM16-LE чанк. Вызывается на КАЖДЫЙ чанк из очереди
     * (все чанки проходят через ring buffer без потерь).
     */
    fun analyze(chunk: ByteArray, out: AudioFeatures) {
        val count = chunk.size / 2
        if (count < 1) return

        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

        // ═══ PHASE 1: Feed ring buffer + ZCR (Gemini) ═══
        var crossings = 0
        var prevSample = if (samplePos > 0) samples[(samplePos - 1) % FFT_SIZE] else 0f
        var chunkRmsSum = 0f

        for (i in 0 until count) {
            val s = buf.short * 3.0517578e-5f // normalize to [-1, 1]
            samples[samplePos % FFT_SIZE] = s
            samplePos++
            chunkRmsSum += s * s

            // ZCR: считаем переходы через ноль (фрикативы = высокий ZCR)
            if (s * prevSample < 0f && abs(s - prevSample) > 0.008f) {
                crossings++
            }
            prevSample = s
        }

        // ZCR normalized [0..1], где 0.5 = чистый шум, >0.25 = фрикатив
        out.zcr = (crossings.toFloat() / count).coerceIn(0f, 1f)

        // Мгновенный RMS (из текущего чанка, не из FFT окна)
        val instantRms = sqrt(chunkRmsSum / count)
        out.rms = (instantRms * 4f).coerceIn(0f, 1f)

        if (out.rms < 0.015f) {
            out.hasVoice = false
            out.energyLow = 0f; out.energyMid = 0f; out.energyHigh = 0f
            out.spectralFlux = 0f; out.isPlosive = false
            return
        }

        // До полного заполнения ring buffer — только RMS и ZCR
        if (samplePos < FFT_SIZE) {
            out.hasVoice = out.rms > 0.03f
            return
        }

        out.hasVoice = true

        // ═══ PHASE 2: FFT + спектральный анализ ═══
        val start = samplePos % FFT_SIZE
        for (i in 0 until FFT_SIZE) {
            val s = samples[(start + i) % FFT_SIZE]
            re[i] = s * window[i]
            im[i] = 0f
        }
        fft(re, im)

        // ═══ PHASE 3: Band energies + Spectral Flux + isPlosive ═══
        var lo = 0f; var mi = 0f; var hi = 0f
        var fluxSum = 0f

        for (bin in 2 until HALF) {
            val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])

            // Rectified Spectral Flux (Gemini): только прирост энергии
            val diff = mag - prevMagnitudes[bin]
            if (diff > 0f) fluxSum += diff
            prevMagnitudes[bin] = mag

            val hz = bin * binRes
            when {
                hz in 150f..800f -> lo += mag
                hz in 800f..2500f -> mi += mag
                hz in 2500f..8000f -> hi += mag
            }
        }

        // Нормализация по ширине полосы (Claude) — убирает перекос от широких полос
        val loBins = ((800f - 150f) / binRes).coerceAtLeast(1f)
        val miBins = ((2500f - 800f) / binRes).coerceAtLeast(1f)
        val hiBins = ((8000f - 2500f) / binRes).coerceAtLeast(1f)

        out.energyLow = (lo / loBins * 2.2f).coerceIn(0f, 1f)
        out.energyMid = (mi / miBins * 3.5f).coerceIn(0f, 1f)
        out.energyHigh = (hi / hiBins * 7f).coerceIn(0f, 1f)

        // Spectral Flux (Gemini + Claude)
        out.spectralFlux = (fluxSum * 0.1f).coerceIn(0f, 1f)

        // isPlosive (Gemini): резкий всплеск + достаточная громкость
        out.isPlosive = out.spectralFlux > 0.35f && out.rms > 0.1f

        // ═══ PHASE 4: Pitch detection (Claude: YIN-lite) ═══
        out.pitch = detectPitch(out.zcr)

        // ═══ PHASE 5: Pitch Variance (Grok) + Baseline Adaptation (Gemini) ═══
        if (out.pitch > 0f) {
            // Adaptive baseline (Gemini): очень медленно подстраивается под голос
            if (!baselineInitialized) {
                baselinePitch = out.pitch
                baselineInitialized = true
            } else {
                baselinePitch += (out.pitch - baselinePitch) * 0.002f // ~0.2%/frame
            }

            // Pitch history → variance (Grok)
            pitchHistory[pitchHistoryIdx % pitchHistory.size] = out.pitch
            pitchHistoryIdx++
            pitchHistoryFilled = minOf(pitchHistoryFilled + 1, pitchHistory.size)

            if (pitchHistoryFilled >= 4) {
                var sum = 0f; var sum2 = 0f; var n = 0
                for (j in 0 until pitchHistoryFilled) {
                    val p = pitchHistory[j]
                    if (p > 0f) { sum += p; sum2 += p * p; n++ }
                }
                if (n > 2) {
                    val mean = sum / n
                    val variance = sum2 / n - mean * mean
                    // Coefficient of variation, normalized to [0..1]
                    out.pitchVariance = (sqrt(max(0f, variance)) / mean * 3f).coerceIn(0f, 1f)
                }
            }
        }
    }

    /**
     * YIN-lite pitch detection (Claude).
     * ZCR используется для подавления ложных срабатываний на шипящих (Gemini insight).
     */
    private fun detectPitch(zcr: Float): Float {
        // Высокий ZCR = шипящие/фрикативы — pitch бессмысленен
        if (zcr > 0.3f) return 0f

        val minD = sampleRate / 400  // 400 Hz max
        val maxD = minOf(sampleRate / 70, FFT_SIZE / 2)  // 70 Hz min
        val start = samplePos % FFT_SIZE
        val n = FFT_SIZE / 2

        // YIN step 3: Cumulative Mean Normalized Difference
        var cumSum = 0f
        var bestD = 0
        var bestVal = Float.MAX_VALUE

        for (d in 1..maxD) {
            var sum = 0f
            for (i in 0 until n) {
                val a = samples[(start + i) % FFT_SIZE]
                val b = samples[(start + i + d) % FFT_SIZE]
                val diff = a - b
                sum += diff * diff
            }

            cumSum += sum
            val cmnd = if (d > 0 && cumSum > 0f) sum * d / cumSum else 1f

            if (d >= minD && cmnd < bestVal && cmnd < 0.25f) {
                bestVal = cmnd
                bestD = d
            }
        }

        return if (bestD > 0) sampleRate.toFloat() / bestD else 0f
    }

    /** Baseline pitch для ProsodyTracker */
    fun getBaselinePitch(): Float = if (baselineInitialized) baselinePitch else 160f

    private fun fft(re: FloatArray, im: FloatArray) {
        for (i in 0 until FFT_SIZE) {
            val r = bitRev[i]
            if (i < r) {
                var t = re[i]; re[i] = re[r]; re[r] = t
                t = im[i]; im[i] = im[r]; im[r] = t
            }
        }
        var size = 2; var half = 1; var step = HALF
        while (size <= FFT_SIZE) {
            var i = 0
            while (i < FFT_SIZE) {
                var k = 0
                for (j in i until i + half) {
                    val jp = j + half
                    val tx = re[jp] * cosT[k] - im[jp] * sinT[k]
                    val ty = re[jp] * sinT[k] + im[jp] * cosT[k]
                    re[jp] = re[j] - tx; im[jp] = im[j] - ty
                    re[j] += tx; im[j] += ty
                    k += step
                }
                i += size
            }
            half = size; size *= 2; step /= 2
        }
    }

    private fun precompute() {
        val phase = (-2.0 * Math.PI / FFT_SIZE).toFloat()
        for (i in 0 until HALF) {
            cosT[i] = cos(phase * i); sinT[i] = sin(phase * i)
        }
        for (i in 0 until FFT_SIZE) {
            var x = i; var r = 0; var s = FFT_SIZE
            while (s > 1) { r = (r shl 1) or (x and 1); x = x shr 1; s = s shr 1 }
            bitRev[i] = r
            window[i] = (0.5f * (1.0f - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
        }
    }
}
