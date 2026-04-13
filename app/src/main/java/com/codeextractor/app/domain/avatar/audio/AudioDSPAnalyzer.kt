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
 * AudioDSPAnalyzer v5 — Zero-Alloc Real-Time DSP
 *
 * ОТЛИЧИЕ ОТ v4:
 *   При RMS < threshold НЕ обнуляет все features.
 *   Вместо этого: hasVoice = false, но energy bands ЗАТУХАЮТ (×0.75).
 *   Это предотвращает мгновенную смерть рта при микропаузах между слогами.
 */
class AudioDSPAnalyzer(private val sampleRate: Int = 24_000) {

    companion object {
        const val FFT_SIZE = 512
        private const val HALF      = FFT_SIZE / 2
        private const val INV_SHORT = 3.0517578e-5f

        private const val YIN_THRESHOLD = 0.22f
        private const val PITCH_MIN_HZ = 70f
        private const val PITCH_MAX_HZ = 400f
        private const val FRICATIVE_ZCR = 0.28f
        private const val FLUX_NORM = 0.08f
        private const val VOICE_RMS_THRESHOLD = 0.018f

        // ── SILENCE DECAY (вместо обнуления!) ─────────────────────────────
        private const val SILENCE_ENERGY_DECAY = 0.75f  // per-chunk decay при тишине
        private const val SILENCE_FLUX_DECAY   = 0.50f
    }

    private val ring    = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var filled  = 0

    private val re      = FloatArray(FFT_SIZE)
    private val im      = FloatArray(FFT_SIZE)

    private val window  = FloatArray(FFT_SIZE)
    private val cosT    = FloatArray(HALF)
    private val sinT    = FloatArray(HALF)
    private val bitRev  = IntArray(FFT_SIZE)

    private val prevMag = FloatArray(HALF)
    private val yinBuf  = FloatArray(HALF)

    private val pitchHistory    = FloatArray(16)
    private var pitchHistIdx    = 0
    private var pitchHistFilled = 0

    private var baselinePitch       = 0f
    private var baselineInitialized = false

    private val binRes: Float = sampleRate.toFloat() / FFT_SIZE

    private val loStart = (150f  / binRes).toInt().coerceAtLeast(2)
    private val loEnd   = (800f  / binRes).toInt().coerceAtMost(HALF - 1)
    private val miStart = (800f  / binRes).toInt()
    private val miEnd   = (2500f / binRes).toInt().coerceAtMost(HALF - 1)
    private val hiStart = (2500f / binRes).toInt()
    private val hiEnd   = (8000f / binRes).toInt().coerceAtMost(HALF - 1)

    private val loBins  = (loEnd - loStart + 1).toFloat()
    private val miBins  = (miEnd - miStart + 1).toFloat()
    private val hiBins  = (hiEnd - hiStart + 1).toFloat()

    init { precompute() }

    fun analyze(chunk: ByteArray, out: AudioFeatures) {
        val sampleCount = chunk.size / 2
        if (sampleCount < 1) return

        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

        var crossings   = 0
        var rmsSum      = 0f
        var prevSample  = if (ringPos > 0) ring[(ringPos - 1 + FFT_SIZE) % FFT_SIZE] else 0f

        repeat(sampleCount) {
            val s = buf.short * INV_SHORT
            ring[ringPos] = s
            ringPos = (ringPos + 1) % FFT_SIZE
            if (filled < FFT_SIZE) filled++
            rmsSum += s * s
            if (s * prevSample < 0f && abs(s - prevSample) > 0.006f) crossings++
            prevSample = s
        }

        val instantRms = sqrt(rmsSum / sampleCount)
        out.rms = (instantRms * 4f).coerceIn(0f, 1f)
        out.zcr = (crossings.toFloat() / sampleCount).coerceIn(0f, 1f)

        // ═══════════════════════════════════════════════════════════════════
        //  КЛЮЧЕВОЙ ФИКС: при тишине НЕ ОБНУЛЯЕМ, а ЗАТУХАЕМ
        // ═══════════════════════════════════════════════════════════════════
        if (out.rms < VOICE_RMS_THRESHOLD) {
            out.hasVoice = false
            // Энергия ПЛАВНО ЗАТУХАЕТ, а не прыгает в ноль
            out.energyLow   *= SILENCE_ENERGY_DECAY
            out.energyMid   *= SILENCE_ENERGY_DECAY
            out.energyHigh  *= SILENCE_ENERGY_DECAY
            out.spectralFlux *= SILENCE_FLUX_DECAY
            out.isPlosive   = false
            // pitch и pitchVariance СОХРАНЯЮТСЯ от последнего валидного значения
            return
        }

        if (filled < FFT_SIZE) {
            out.hasVoice = out.rms > 0.03f
            return
        }

        out.hasVoice = true

        val start = ringPos
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(start + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft()

        var lo = 0f; var mi = 0f; var hi = 0f
        var flux = 0f

        for (bin in 2 until HALF) {
            val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            val diff = mag - prevMag[bin]
            if (diff > 0f) flux += diff
            prevMag[bin] = mag

            when {
                bin in loStart..loEnd -> lo += mag
                bin in miStart..miEnd -> mi += mag
                bin in hiStart..hiEnd -> hi += mag
            }
        }

        out.energyLow  = (lo / loBins * 2.2f).coerceIn(0f, 1f)
        out.energyMid  = (mi / miBins * 3.5f).coerceIn(0f, 1f)
        out.energyHigh = (hi / hiBins * 7.0f).coerceIn(0f, 1f)

        out.spectralFlux = (flux * FLUX_NORM).coerceIn(0f, 1f)
        out.isPlosive    = out.spectralFlux > 0.32f && out.rms > 0.09f

        out.pitch = if (out.zcr > FRICATIVE_ZCR) 0f else detectPitchYin()

        if (out.pitch > 0f) {
            updateBaseline(out.pitch)
            updatePitchVariance(out.pitch)
            out.pitchVariance = computeVariance()
        } else {
            out.pitchVariance = 0f
        }
    }

    fun getBaselinePitch(): Float = if (baselineInitialized) baselinePitch else 160f

    fun reset() {
        ring.fill(0f); ringPos = 0; filled = 0
        re.fill(0f); im.fill(0f)
        prevMag.fill(0f); yinBuf.fill(0f)
        pitchHistory.fill(0f); pitchHistIdx = 0; pitchHistFilled = 0
        baselinePitch = 0f; baselineInitialized = false
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PITCH DETECTION (YIN)
    // ═══════════════════════════════════════════════════════════════════════

    private fun detectPitchYin(): Float {
        val minLag = (sampleRate / PITCH_MAX_HZ).toInt()
        val maxLag = min((sampleRate / PITCH_MIN_HZ).toInt(), HALF - 1)
        val n      = HALF
        val start  = ringPos

        for (lag in 0..maxLag) {
            var sum = 0f
            for (i in 0 until n) {
                val a = ring[(start + i) % FFT_SIZE]
                val b = ring[(start + i + lag) % FFT_SIZE]
                val d = a - b
                sum += d * d
            }
            yinBuf[lag] = sum
        }

        yinBuf[0] = 1f
        var cumSum = 0f
        for (lag in 1..maxLag) {
            cumSum += yinBuf[lag]
            yinBuf[lag] = if (cumSum > 0f) yinBuf[lag] * lag / cumSum else 1f
        }

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

    private fun updateBaseline(pitch: Float) {
        if (!baselineInitialized) {
            baselinePitch = pitch
            baselineInitialized = true
        } else {
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
        val mean = sum / n
        val variance = max(0f, sum2 / n - mean * mean)
        return (sqrt(variance) / mean * 3f).coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FFT
    // ═══════════════════════════════════════════════════════════════════════

    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val r = bitRev[i]
            if (i < r) {
                var t = re[i]; re[i] = re[r]; re[r] = t
                t     = im[i]; im[i] = im[r]; im[r] = t
            }
        }
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

    private fun precompute() {
        val twoPi = 2.0 * Math.PI
        for (i in 0 until FFT_SIZE) {
            window[i] = (0.5 * (1.0 - cos(twoPi * i / (FFT_SIZE - 1)))).toFloat()
        }
        val phase = (-twoPi / FFT_SIZE).toFloat()
        for (i in 0 until HALF) {
            cosT[i] = cos(phase * i)
            sinT[i] = sin(phase * i)
        }
        val bits = log2(FFT_SIZE.toFloat()).toInt()
        for (i in 0 until FFT_SIZE) {
            var x = i; var r = 0
            repeat(bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            bitRev[i] = r
        }
    }
}
