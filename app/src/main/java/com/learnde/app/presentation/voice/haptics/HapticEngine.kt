package com.learnde.app.presentation.voice.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Audio-Haptic Feedback — микро-вибрация в такт голосу ИИ.
 *
 * Анализирует RMS (Root Mean Square) PCM-данных и генерирует
 * пропорциональную тактильную обратную связь.
 * Создаёт ощущение «живого» голоса в руках.
 */
@Singleton
class HapticEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile
    var enabled: Boolean = true

    /** RMS порог срабатывания (0.0 — 1.0). Ниже — тишина, не вибрируем */
    var threshold: Float = 0.12f

    /**
     * Подписывается на поток PCM-аудио и вибрирует в такт.
     * Вызывать один раз при старте сессии.
     */
    suspend fun attachToAudioStream(pcmStream: Flow<ByteArray>) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        pcmStream.collectLatest { chunk ->
            if (!enabled || chunk.size < 4) return@collectLatest

            val rms = calculateRms(chunk)

            if (rms > threshold && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Интенсивность пропорциональна громкости (1..180)
                val intensity = (rms * 200).toInt().coerceIn(1, 180)
                val effect = VibrationEffect.createOneShot(15L, intensity)
                vibrator.vibrate(effect)
            }
        }
    }

    /**
     * Вычисляет RMS (среднеквадратичное значение) громкости PCM 16-bit LE.
     */
    private fun calculateRms(pcmData: ByteArray): Float {
        var sumSquare = 0.0
        val sampleCount = pcmData.size / 2

        for (i in 0 until pcmData.size - 1 step 2) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val normalized = sample.toShort().toFloat() / Short.MAX_VALUE
            sumSquare += normalized * normalized
        }

        return if (sampleCount > 0) sqrt(sumSquare / sampleCount).toFloat() else 0f
    }
}
