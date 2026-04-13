package com.codeextractor.app.domain.avatar.audio

import com.codeextractor.app.domain.avatar.AudioFeatures
import com.codeextractor.app.domain.avatar.EmotionalProsody
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ProsodyTracker v4 — Cognitive Load & Eureka Rebound System
 *
 * Модель эмоций строится на трёх независимых осях:
 *
 *   VALENCE      [-1..+1]  знак эмоции (грусть ↔ радость)
 *   AROUSAL      [ 0.. 1]  интенсивность (спокойно ↔ возбуждённо)
 *   THOUGHTFULNESS [0..1]  когнитивная нагрузка (речь ↔ обдумывание)
 *
 * Ключевые инновации:
 *
 *   1. COGNITIVE PRESSURE SPRING
 *      Нелинейный spring-аккумулятор нарастает во время пауз и сетевых лагов.
 *      Нарастание: asymptotic (быстрый старт → насыщение у 1.0).
 *      Убывание: экспоненциальный decay во время активной речи.
 *
 *   2. EUREKA REBOUND
 *      Когда первый аудиочанк прерывает паузу с накопленным давлением > 0.4,
 *      происходит резкий spike Arousal («Озарение!»).
 *      Размер спайка пропорционален накопленному давлению.
 *      После спайка давление обнуляется.
 *
 *   3. ADAPTIVE PITCH BASELINE
 *      Очень медленная адаптация (~0.05%/кадр) к голосу спикера.
 *      Valence считается по ОТКЛОНЕНИЮ от базового тона, а не по
 *      абсолютному значению — это устраняет смещение между голосами.
 *
 *   4. DYNAMIC RANGE TRACKING
 *      Скользящее окно RMS (10 кадров) для оценки динамического диапазона.
 *      Вялая монотонная речь и экспрессивная — различаются автоматически.
 *
 *   5. ASYMMETRIC AROUSAL ENVELOPE
 *      Attack: быстрый (10x/сек) — реакция на взрывные звуки мгновенна.
 *      Release: медленный (2.5x/сек) — эмоция угасает органично.
 *
 * Thread-safety: single writer (animator coroutine), никакой синхронизации не нужно.
 */
class ProsodyTracker {

    companion object {
        // ── Cognitive Pressure ───────────────────────────────────────────
        private const val PRESSURE_ONSET_MS      = 400L    // пауза до начала накопления
        private const val PRESSURE_RISE_SPEED    = 1.2f    // скорость нарастания (asymptotic)
        private const val PRESSURE_DECAY_SPEED   = 3.5f    // скорость спада при речи
        private const val EUREKA_THRESHOLD       = 0.38f   // минимальное давление для спайка
        private const val EUREKA_AROUSAL_BOOST   = 0.65f   // множитель спайка Arousal
        private const val EUREKA_BOOST_SCALE     = EUREKA_AROUSAL_BOOST

        // ── Arousal envelope ────────────────────────────────────────────
        private const val AROUSAL_ATTACK         = 10f
        private const val AROUSAL_RELEASE        = 2.5f
        private const val AROUSAL_SILENCE_DECAY  = 1.4f

        // ── Valence ──────────────────────────────────────────────────────
        private const val VALENCE_SMOOTH_SPEED   = 3.0f
        private const val VALENCE_SILENCE_DECAY  = 0.7f
        private const val BASELINE_ADAPT_RATE    = 0.0005f // ~0.05%/кадр

        // ── Pitch delta thresholds (в Гц от baseline) ───────────────────
        private const val PITCH_JOY_HIGH         = 22f
        private const val PITCH_ENTHUSIASM       = 14f
        private const val PITCH_EXPRESSIVE       = 9f
        private const val PITCH_FRIENDLY         = 6f
        private const val PITCH_IRRITATION_LOW   = -14f
        private const val PITCH_SADNESS_LOW      = -9f

        // ── Dynamic range ────────────────────────────────────────────────
        private const val RMS_HISTORY_SIZE       = 10
        private const val RMS_MIN_VALID          = 0.01f
    }

    // ── Pitch smoothing ───────────────────────────────────────────────────
    private var smoothPitch         = 0f
    private var baselinePitch       = 0f
    private var baselineInitialized = false

    // ── Cognitive Pressure spring ─────────────────────────────────────────
    private var silenceMs           = 0L
    private var eurekaPending       = false   // флаг: ждём первый чанк после паузы

    // ── Dynamic range tracking ────────────────────────────────────────────
    private val rmsHistory = FloatArray(RMS_HISTORY_SIZE)
    private var rmsIdx     = 0
    private var energyDynamicRange = 0f

    // ── Previous frame ────────────────────────────────────────────────────
    private var prevRms = 0f

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Обновляет [prosody] на основе текущего кадра [features].
     *
     * @param features       аудиопризнаки текущего кадра (из AudioDSPAnalyzer)
     * @param prosody        мутабельный вектор эмоций (изменяется in-place)
     * @param dtMs           delta time в миллисекундах
     * @param networkHold    true = сеть лагает, Gemini ещё не прислал чанк
     *                       (пробрасывается из AvatarAnimatorImpl.ANTICIPATION state)
     */
    fun update(
        features: AudioFeatures,
        prosody: EmotionalProsody,
        dtMs: Long,
        networkHold: Boolean = false,
    ) {
        val dt = dtMs.coerceIn(1, 32) / 1000f

        // ── Ветвь 1: ТИШИНА (нет голоса, нет сетевого удержания) ──────────
        val isSilent = !features.hasVoice &&
                       features.zcr < 0.1f &&
                       !networkHold

        if (isSilent) {
            handleSilence(prosody, dtMs, dt)
            prevRms = 0f
            return
        }

        // ── Ветвь 2: ANTICIPATION (сеть лагает, голоса нет) ───────────────
        if (networkHold && !features.hasVoice) {
            handleNetworkHold(prosody, dtMs, dt)
            return
        }

        // ── Ветвь 3: АКТИВНАЯ РЕЧЬ ─────────────────────────────────────────
        handleActiveVoice(features, prosody, dt)

        prevRms = features.rms
    }

    fun reset() {
        smoothPitch = 0f; baselinePitch = 0f; baselineInitialized = false
        silenceMs = 0L; eurekaPending = false
        rmsHistory.fill(0f); rmsIdx = 0; energyDynamicRange = 0f
        prevRms = 0f
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PRIVATE: три ветви state machine
    // ═══════════════════════════════════════════════════════════════════════

    // ── ТИШИНА ───────────────────────────────────────────────────────────
    private fun handleSilence(prosody: EmotionalProsody, dtMs: Long, dt: Float) {
        silenceMs += dtMs

        // Decay Arousal
        prosody.arousal = max(0f, prosody.arousal - dt * AROUSAL_SILENCE_DECAY)

        // Decay Valence к нейтрали
        prosody.valence *= (1f - dt * VALENCE_SILENCE_DECAY)

        // Cognitive Pressure начинает накапливаться после ONSET
        if (silenceMs > PRESSURE_ONSET_MS) {
            // Asymptotic rise: давление стремится к 1.0, но никогда не достигает
            prosody.cognitivePressure +=
                (1f - prosody.cognitivePressure) * PRESSURE_RISE_SPEED * dt

            // Как только давление значимое — взводим флаг Эврики
            if (prosody.cognitivePressure > EUREKA_THRESHOLD) {
                eurekaPending = true
            }
        }

        // Thoughtfulness = прямое отражение cognitive pressure
        prosody.thoughtfulness = prosody.cognitivePressure
    }

    // ── NETWORK HOLD (ANTICIPATION) ───────────────────────────────────────
    private fun handleNetworkHold(prosody: EmotionalProsody, dtMs: Long, dt: Float) {
        // Ведём себя как «пауза подбора слова»: давление нарастает,
        // но Arousal НЕ падает (аватар не расслабляется, а «формулирует»)
        silenceMs += dtMs

        if (silenceMs > PRESSURE_ONSET_MS) {
            prosody.cognitivePressure +=
                (1f - prosody.cognitivePressure) * PRESSURE_RISE_SPEED * dt
            eurekaPending = prosody.cognitivePressure > EUREKA_THRESHOLD
        }

        prosody.thoughtfulness = prosody.cognitivePressure

        // Лёгкий decay Valence, но не Arousal
        prosody.valence *= (1f - dt * 0.35f)
    }

    // ── АКТИВНАЯ РЕЧЬ ─────────────────────────────────────────────────────
    private fun handleActiveVoice(
        features: AudioFeatures,
        prosody: EmotionalProsody,
        dt: Float,
    ) {
        // Сбрасываем счётчик тишины
        silenceMs = 0L

        // ── EUREKA REBOUND ───────────────────────────────────────────────
        if (eurekaPending && features.hasVoice) {
            // Спайк Arousal пропорционален накопленному давлению
            val spike = prosody.cognitivePressure * EUREKA_BOOST_SCALE
            prosody.arousal = (prosody.arousal + spike).coerceAtMost(1f)

            // Сброс давления
            prosody.cognitivePressure = 0f
            eurekaPending = false
        }

        // Плавный decay cognitive pressure во время речи
        prosody.cognitivePressure =
            max(0f, prosody.cognitivePressure - dt * PRESSURE_DECAY_SPEED)
        prosody.thoughtfulness = prosody.cognitivePressure

        // ── DYNAMIC RANGE ────────────────────────────────────────────────
        rmsHistory[rmsIdx % RMS_HISTORY_SIZE] = features.rms
        rmsIdx++
        if (rmsIdx >= RMS_HISTORY_SIZE) {
            var minR = 1f; var maxR = 0f
            for (r in rmsHistory) {
                if (r > RMS_MIN_VALID) {
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                }
            }
            energyDynamicRange = (maxR - minR).coerceIn(0f, 1f)
        }

        // ── AROUSAL ──────────────────────────────────────────────────────
        val rmsSpike      = max(0f, (features.rms - prevRms) * 6f)
        val fluxContrib   = features.spectralFlux * 2.2f
        val pitchVarContr = features.pitchVariance * 1.8f
        val dynContrib    = energyDynamicRange * 0.15f
        val rmsContrib    = features.rms * 0.05f

        val arousalTarget = (
            rmsSpike    * 0.30f +
            fluxContrib * 0.28f +
            pitchVarContr * 0.22f +
            dynContrib  +
            rmsContrib
        ).coerceIn(0f, 1f)

        // Asymmetric envelope: быстрый attack, медленный release
        val arousalSpeed = if (arousalTarget > prosody.arousal)
            AROUSAL_ATTACK else AROUSAL_RELEASE
        prosody.arousal +=
            (arousalTarget - prosody.arousal) * arousalSpeed * dt

        // ── PITCH → VALENCE ───────────────────────────────────────────────
        if (features.pitch > 0f) {
            updateValence(features, prosody, dt)
        }

        // ── CLAMP ─────────────────────────────────────────────────────────
        prosody.valence = prosody.valence.coerceIn(-1f, 1f)
        prosody.arousal = prosody.arousal.coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VALENCE via adaptive pitch baseline
    // ═══════════════════════════════════════════════════════════════════════

    private fun updateValence(
        features: AudioFeatures,
        prosody: EmotionalProsody,
        dt: Float,
    ) {
        // Smooth pitch tracking (быстрее baseline — ловит интонационные подъёмы)
        if (smoothPitch == 0f) smoothPitch = features.pitch
        smoothPitch += (features.pitch - smoothPitch) * 12f * dt

        // Adaptive baseline — очень медленно подстраивается под голос спикера
        if (!baselineInitialized) {
            baselinePitch = features.pitch
            baselineInitialized = true
        } else {
            baselinePitch += (features.pitch - baselinePitch) * BASELINE_ADAPT_RATE
        }

        val delta = smoothPitch - baselinePitch
        val pVar  = features.pitchVariance

        // Pitch variance boost (Grok): экспрессивная речь усиливает эмоциональный сигнал
        val exprBoost = 1f + pVar * 0.45f

        val targetValence: Float = when {
            // Радость / смех: высокий тон + вариация + много ВЧ
            delta > PITCH_JOY_HIGH &&
            pVar > 0.28f &&
            features.energyHigh > 0.12f    -> 0.85f

            // Энтузиазм: подъём тона + возбуждение
            delta > PITCH_ENTHUSIASM &&
            prosody.arousal > 0.35f         -> 0.52f

            // Экспрессивная позитивность
            delta > PITCH_EXPRESSIVE &&
            pVar > 0.22f                    -> 0.32f

            // Дружелюбие: небольшой подъём
            delta > PITCH_FRIENDLY          -> 0.18f

            // Раздражение: резкий низкий тон + высокий Arousal
            delta < PITCH_IRRITATION_LOW &&
            prosody.arousal > 0.48f         -> -0.58f

            // Грусть: пониженный тон + монотонность + тихо
            delta < PITCH_SADNESS_LOW &&
            features.rms < 0.18f &&
            pVar < 0.10f                    -> -0.38f

            else -> 0f
        }

        val boostedTarget = (targetValence * exprBoost).coerceIn(-1f, 1f)
        prosody.valence +=
            (boostedTarget - prosody.valence) * VALENCE_SMOOTH_SPEED * dt
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  COMPANION HELPERS
    // ═══════════════════════════════════════════════════════════════════════

}