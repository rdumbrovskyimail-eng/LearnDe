package com.codeextractor.app.domain.avatar.audio

import com.codeextractor.app.domain.avatar.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var samplePos = 0 // ring position in samples buffer

    init { precompute() }

    /**
     * Process PCM16-LE chunk, extract features into provided object.
     * Handles variable chunk sizes via internal ring buffer.
     */
    fun analyze(chunk: ByteArray, out: AudioFeatures) {
        val count = chunk.size / 2
        if (count < 1) return

        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

        // Feed samples into ring buffer
        for (i in 0 until count) {
            samples[samplePos % FFT_SIZE] = buf.short * 3.0517578e-5f // 1/32768
            samplePos++
        }

        // Need at least FFT_SIZE samples accumulated
        if (samplePos < FFT_SIZE) return

        // Copy ring buffer → FFT input with Hann window, unwrap ring
        val start = samplePos % FFT_SIZE
        var rmsSum = 0f
        for (i in 0 until FFT_SIZE) {
            val s = samples[(start + i) % FFT_SIZE]
            re[i] = s * window[i]
            im[i] = 0f
            rmsSum += s * s
        }

        // RMS
        out.rms = (sqrt(rmsSum / FFT_SIZE) * 4f).coerceIn(0f, 1f)
        if (out.rms < 0.02f) {
            out.hasVoice = false; out.energyLow = 0f; out.energyMid = 0f; out.energyHigh = 0f
            return
        }
        out.hasVoice = true

        // FFT
        fft(re, im)

        // Band energies
        var lo = 0f; var mi = 0f; var hi = 0f
        for (bin in 2 until HALF) {
            val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            val hz = bin * binRes
            when {
                hz in 150f..800f -> lo += mag
                hz in 800f..2500f -> mi += mag
                hz in 2500f..8000f -> hi += mag
            }
        }
        out.energyLow = (lo * 0.05f).coerceIn(0f, 1f)
        out.energyMid = (mi * 0.08f).coerceIn(0f, 1f)
        out.energyHigh = (hi * 0.25f).coerceIn(0f, 1f)

        // Simple pitch via autocorrelation
        out.pitch = detectPitch()
    }

    /** AMDF-based pitch detection */
    private fun detectPitch(): Float {
        val minD = sampleRate / 400  // 400 Hz max
        val maxD = sampleRate / 70   // 70 Hz min
        val start = samplePos % FFT_SIZE
        var bestD = 0; var bestVal = Float.MAX_VALUE

        for (d in minD..minOf(maxD, FFT_SIZE / 2)) {
            var sum = 0f
            val n = FFT_SIZE / 2
            for (i in 0 until n) {
                val a = samples[(start + i) % FFT_SIZE]
                val b = samples[(start + i + d) % FFT_SIZE]
                sum += (a - b) * (a - b)
            }
            if (sum < bestVal) { bestVal = sum; bestD = d }
        }
        return if (bestD > 0 && bestVal < 0.5f) sampleRate.toFloat() / bestD else 0f
    }

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