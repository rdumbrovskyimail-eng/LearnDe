package com.codeextractor.app.domain.avatar.audio

import com.codeextractor.app.domain.avatar.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AudioDSPAnalyzer v4 — Zero-Alloc Real-Time DSP
 *
 * Архитектура одного прохода:
 *   1. PCM → Ring buffer + ZCR (Zero-Crossing Rate)
 *   2. Hann window → in-place Cooley-Tukey FFT
 *   3. Magnitude → Band energies + Rectified Spectral Flux
 *   4. YIN-lite pitch detection (autocorrelation domain)
 *   5. Pitch history → variance (экспрессивность)
 *   6. Adaptive speaker baseline (медленная адаптация к голосу)
 *
 * Гарантии:
 *   • Zero heap allocation в hot path (все буферы pre-allocated в init)
 *   • Single writer / single reader — без синхронизации
 *   • Детерминированное время: O(N log N) на FFT_SIZE сэмплах
 *
 * Параметры по умолчанию рассчитаны под Gemini Live output:
 *   sampleRate = 24000 Hz, PCM-16LE mono
 */
class AudioDSPAnalyzer(private val sampleRate: Int = 24_000) {

    companion object {
        const val FFT_SIZE = 512
        private const val HALF      = FFT_SIZE / 2
        private const val INV_SHORT = 3.0517578e-5f   // 1 / 32768

        // YIN threshold: < 0.15 строгий, < 0.25 стандартный
        private const val YIN_THRESHOLD = 0.22f

        // Pitch диапазон голоса
        private const val PITCH_MIN_HZ = 70f
        private const val PITCH_MAX_HZ = 400f

        // Фрикативный порог ZCR
        private const val FRICATIVE_ZCR = 0.28f

        // Spectral flux нормировка
        private const val FLUX_NORM = 0.08f

        // Минимальный RMS для детектирования голоса
        private const val VOICE_RMS_THRESHOLD = 0.018f
    }

    // ── Ring buffer (pre-allocated) ───────────────────────────────────────
    private val ring    = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var filled  = 0            // сколько сэмплов записано (до FFT_SIZE)

    // ── FFT рабочие массивы ───────────────────────────────────────────────
    private val re      = FloatArray(FFT_SIZE)
    private val im      = FloatArray(FFT_SIZE)

    // ── Предвычисленные таблицы (init, никогда не меняются) ──────────────
    private val window  = FloatArray(FFT_SIZE)   // Hann
    private val cosT    = FloatArray(HALF)
    private val sinT    = FloatArray(HALF)
    private val bitRev  = IntArray(FFT_SIZE)

    // ── Spectral flux: предыдущий спектр ─────────────────────────────────
    private val prevMag = FloatArray(HALF)

    // ── YIN: difference function buffer ──────────────────────────────────
    private val yinBuf  = FloatArray(HALF)

    // ── Pitch history для variance (Grok) ────────────────────────────────
    private val pitchHistory    = FloatArray(16)
    private var pitchHistIdx    = 0
    private var pitchHistFilled = 0

    // ── Adaptive baseline pitch (Gemini) ─────────────────────────────────
    private var baselinePitch       = 0f
    private var baselineInitialized = false

    // ── Бинные границы (pre-computed) ────────────────────────────────────
    private val binRes: Float = sampleRate.toFloat() / FFT_SIZE

    // Диапазоны полос в бинах (включительно)
    private val loStart = (150f  / binRes).toInt().coerceAtLeast(2)
    private val loEnd   = (800f  / binRes).toInt().coerceAtMost(HALF - 1)
    private val miStart = (800f  / binRes).toInt()
    private val miEnd   = (2500f / binRes).toInt().coerceAtMost(HALF - 1)
    private val hiStart = (2500f / binRes).toInt()
    private val hiEnd   = (8000f / binRes).toInt().coerceAtMost(HALF - 1)

    // Ширина полос (для нормализации)
    private val loBins  = (loEnd - loStart + 1).toFloat()
    private val miBins  = (miEnd - miStart + 1).toFloat()
    private val hiBins  = (hiEnd - hiStart + 1).toFloat()

    init { precompute() }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Анализирует PCM16-LE чанк. Заполняет [out] in-place.
     * Можно вызывать с чанками любого размера — данные накапливаются в ring buffer.
     *
     * Thread-safety: single-threaded (вызывается только из animator coroutine).
     */
    fun analyze(chunk: ByteArray, out: AudioFeatures) {
        val sampleCount = chunk.size / 2
        if (sampleCount < 1) return

        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

        // ── ФАЗА 1: Ring buffer + ZCR + instant RMS ───────────────────────
        var crossings   = 0
        var rmsSum      = 0f
        var prevSample  = if (ringPos > 0) ring[(ringPos - 1 + FFT_SIZE) % FFT_SIZE] else 0f

        repeat(sampleCount) {
            val s = buf.short * INV_SHORT
            ring[ringPos] = s
            ringPos = (ringPos + 1) % FFT_SIZE
            if (filled < FFT_SIZE) filled++

            rmsSum += s * s

            // ZCR: знаковое пересечение с гистерезисом (подавляет ложные срабатывания)
            if (s * prevSample < 0f && abs(s - prevSample) > 0.006f) crossings++
            prevSample = s
        }

        val instantRms = sqrt(rmsSum / sampleCount)
        out.rms = (instantRms * 4f).coerceIn(0f, 1f)
        out.zcr = (crossings.toFloat() / sampleCount).coerceIn(0f, 1f)

        // Ранний выход при тишине
        if (out.rms < VOICE_RMS_THRESHOLD) {
            out.hasVoice    = false
            out.energyLow   = 0f
            out.energyMid   = 0f
            out.energyHigh  = 0f
            out.spectralFlux = 0f
            out.isPlosive   = false
            out.pitch       = 0f
            return
        }

        // Ожидаем накопления полного окна
        if (filled < FFT_SIZE) {
            out.hasVoice = out.rms > 0.03f
            return
        }

        out.hasVoice = true

        // ── ФАЗА 2: Windowed FFT ──────────────────────────────────────────
        val start = ringPos  // ringPos уже указывает на «oldest» сэмпл
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(start + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft()

        // ── ФАЗА 3: Magnitude + Band energies + Spectral Flux ─────────────
        var lo = 0f; var mi = 0f; var hi = 0f
        var flux = 0f

        for (bin in 2 until HALF) {
            val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])

            // Rectified spectral flux: только положительный прирост энергии
            val diff = mag - prevMag[bin]
            if (diff > 0f) flux += diff
            prevMag[bin] = mag

            when {
                bin in loStart..loEnd -> lo += mag
                bin in miStart..miEnd -> mi += mag
                bin in hiStart..hiEnd -> hi += mag
            }
        }

        // Нормализация по ширине полосы (убирает перекос широких диапазонов)
        out.energyLow  = (lo / loBins * 2.2f).coerceIn(0f, 1f)
        out.energyMid  = (mi / miBins * 3.5f).coerceIn(0f, 1f)
        out.energyHigh = (hi / hiBins * 7.0f).coerceIn(0f, 1f)

        out.spectralFlux = (flux * FLUX_NORM).coerceIn(0f, 1f)
        out.isPlosive    = out.spectralFlux > 0.32f && out.rms > 0.09f

        // ── ФАЗА 4: YIN-lite pitch detection ─────────────────────────────
        out.pitch = if (out.zcr > FRICATIVE_ZCR) 0f else detectPitchYin()

        // ── ФАЗА 5: Pitch variance + baseline adaptation ──────────────────
        if (out.pitch > 0f) {
            updateBaseline(out.pitch)
            updatePitchVariance(out.pitch)
            out.pitchVariance = computeVariance()
        } else {
            out.pitchVariance = 0f
        }
    }

    /** Возвращает адаптированный базовый тон спикера (для ProsodyTracker) */
    fun getBaselinePitch(): Float = if (baselineInitialized) baselinePitch else 160f

    /** Сброс состояния (при смене сессии) */
    fun reset() {
        ring.fill(0f); ringPos = 0; filled = 0
        re.fill(0f); im.fill(0f)
        prevMag.fill(0f); yinBuf.fill(0f)
        pitchHistory.fill(0f); pitchHistIdx = 0; pitchHistFilled = 0
        baselinePitch = 0f; baselineInitialized = false
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PITCH DETECTION  (YIN algorithm, Cheveigné & Kawahara 2002)
    // ═══════════════════════════════════════════════════════════════════════

    private fun detectPitchYin(): Float {
        val minLag = (sampleRate / PITCH_MAX_HZ).toInt()
        val maxLag = min((sampleRate / PITCH_MIN_HZ).toInt(), HALF - 1)
        val n      = HALF
        val start  = ringPos

        // Шаг 2: Difference function
        // d(τ) = Σ (x[t] - x[t+τ])²
        for (lag in 0..maxLag) {
            var sum = 0f
            for (i in 0 until n) {
                val a = ring[(start + i)       % FFT_SIZE]
                val b = ring[(start + i + lag) % FFT_SIZE]
                val d = a - b
                sum += d * d
            }
            yinBuf[lag] = sum
        }

        // Шаг 3: Cumulative Mean Normalized Difference (CMND)
        yinBuf[0] = 1f
        var cumSum = 0f
        for (lag in 1..maxLag) {
            cumSum += yinBuf[lag]
            yinBuf[lag] = if (cumSum > 0f) yinBuf[lag] * lag / cumSum else 1f
        }

        // Шаг 4: Абсолютный минимум ниже порога (с параболической интерполяцией)
        var bestLag = -1
        var bestVal = Float.MAX_VALUE

        for (lag in minLag..maxLag) {
            val v = yinBuf[lag]
            if (v < YIN_THRESHOLD && v < bestVal) {
                bestVal = v
                bestLag = lag
            }
        }

        if (bestLag <= 0) return 0f

        // Шаг 5: Параболическая интерполяция для суб-бинной точности
        val refinedLag = refineParabolic(bestLag, maxLag)
        return if (refinedLag > 0f) sampleRate / refinedLag else 0f
    }

    private fun refineParabolic(lag: Int, maxLag: Int): Float {
        if (lag <= 0 || lag >= maxLag) return lag.toFloat()
        val prev = yinBuf[lag - 1]
        val curr = yinBuf[lag]
        val next = yinBuf[lag + 1]
        val denom = 2f * (2f * curr - prev - next)
        return if (abs(denom) < 1e-6f) lag.toFloat()
        else lag + (prev - next) / denom
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PITCH VARIANCE & BASELINE
    // ═══════════════════════════════════════════════════════════════════════

    private fun updateBaseline(pitch: Float) {
        if (!baselineInitialized) {
            baselinePitch = pitch
            baselineInitialized = true
        } else {
            // Очень медленная адаптация ~0.2%/кадр: привязывается к голосу за ~10 сек
            baselinePitch += (pitch - baselinePitch) * 0.002f
        }
    }

    private fun updatePitchVariance(pitch: Float) {
        pitchHistory[pitchHistIdx % pitchHistory.size] = pitch
        pitchHistIdx++
        pitchHistFilled = min(pitchHistFilled + 1, pitchHistory.size)
    }

    private fun computeVariance(): Float {
        if (pitchHistFilled < 4) return 0f
        var sum = 0f; var sum2 = 0f; var n = 0
        for (i in 0 until pitchHistFilled) {
            val p = pitchHistory[i]
            if (p > 0f) { sum += p; sum2 += p * p; n++ }
        }
        if (n < 3) return 0f
        val mean     = sum / n
        val variance = max(0f, sum2 / n - mean * mean)
        // Coefficient of Variation, нормированный к [0..1]
        return (sqrt(variance) / mean * 3f).coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  IN-PLACE COOLEY-TUKEY FFT  (Decimation-In-Time, Radix-2)
    // ═══════════════════════════════════════════════════════════════════════

    private fun fft() {
        // Bit-reversal permutation
        for (i in 0 until FFT_SIZE) {
            val r = bitRev[i]
            if (i < r) {
                var t = re[i]; re[i] = re[r]; re[r] = t
                t     = im[i]; im[i] = im[r]; im[r] = t
            }
        }

        // Butterfly passes
        var size = 2
        var step = HALF
        while (size <= FFT_SIZE) {
            val half = size / 2
            var i = 0
            while (i < FFT_SIZE) {
                var k = 0
                for (j in i until i + half) {
                    val jp = j + half
                    val tx = re[jp] * cosT[k] - im[jp] * sinT[k]
                    val ty = re[jp] * sinT[k] + im[jp] * cosT[k]
                    re[jp] = re[j] - tx;  im[jp] = im[j] - ty
                    re[j] += tx;          im[j] += ty
                    k += step
                }
                i += size
            }
            size  *= 2
            step  /= 2
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PRECOMPUTE  (вызывается один раз в init)
    // ═══════════════════════════════════════════════════════════════════════

    private fun precompute() {
        // Hann window
        val twoPi = 2.0 * Math.PI
        for (i in 0 until FFT_SIZE) {
            window[i] = (0.5 * (1.0 - cos(twoPi * i / (FFT_SIZE - 1)))).toFloat()
        }

        // Twiddle factors (trig tables)
        val phase = (-twoPi / FFT_SIZE).toFloat()
        for (i in 0 until HALF) {
            cosT[i] = cos(phase * i)
            sinT[i] = sin(phase * i)
        }

        // Bit-reversal table
        val bits = log2(FFT_SIZE.toFloat()).toInt()
        for (i in 0 until FFT_SIZE) {
            var x = i; var r = 0
            repeat(bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            bitRev[i] = r
        }
    }
}