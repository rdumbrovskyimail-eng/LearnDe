package com.codeextractor.app.domain.avatar

/**
 * SpeechFlowController v2 — Uses smoothed audio, not raw per-chunk values.
 *
 * ФИКС: momentum теперь считается по audioActivity (сглаженный),
 * а НЕ по raw hasVoice/rms. Один тихий чанк больше не убивает momentum.
 *
 * audioActivity поднимается мгновенно (12/сек) при голосе,
 * падает МЕДЛЕННО (1.5/сек) при тишине.
 * Momentum следует за audioActivity, а не за raw данными.
 */
class SpeechFlowController {

    companion object {
        private const val MOMENTUM_DECAY_SPEAKING    = 0.0f
        private const val MOMENTUM_DECAY_SHORT_PAUSE = 0.20f
        private const val MOMENTUM_DECAY_LONG_PAUSE  = 0.35f
        private const val MOMENTUM_DECAY_TURN_ENDED  = 0.50f
        private const val MOMENTUM_DECAY_BARGE_IN    = 8.0f

        private const val MOMENTUM_RISE_AUDIO        = 6.0f
        private const val MOMENTUM_RISE_TEXT          = 3.0f

        // ── Сглаживание аудио ─────────────────────────────────────────────
        private const val AUDIO_RISE_SPEED           = 12.0f
        private const val AUDIO_FALL_SPEED           = 1.5f   // МЕДЛЕННЕЕ чем v1 (было 2.0)

        // ── Порог для "голос активен" по СГЛАЖЕННЫМ данным ────────────────
        private const val ACTIVITY_VOICE_THR         = 0.08f  // audioActivity > 0.08 = голос

        private const val SHORT_PAUSE_THRESHOLD_MS   = 600L
        private const val LONG_PAUSE_THRESHOLD_MS    = 1500L
        private const val VOICE_RMS_THRESHOLD        = 0.022f
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OUTPUTS
    // ══════════════════════════════════════════════════════════════════════

    var speechMomentum: Float = 0f; private set
    val isInSpeechFlow: Boolean get() = speechMomentum > 0.05f
    var audioActivity: Float = 0f; private set
    var textAvailable: Boolean = false; private set
    var pauseDepth: Float = 0f; private set

    // ══════════════════════════════════════════════════════════════════════
    //  INTERNAL
    // ══════════════════════════════════════════════════════════════════════

    private var silenceDurationMs: Long = 0L
    private var turnEnded: Boolean = false
    private var bargeInActive: Boolean = false

    // ══════════════════════════════════════════════════════════════════════
    //  EVENTS
    // ══════════════════════════════════════════════════════════════════════

    fun onAudioChunk(pcmBytes: Int, sampleRate: Int = 24_000) {
        bargeInActive = false
        turnEnded = false
    }

    fun onTextChunk() {
        bargeInActive = false
        turnEnded = false
    }

    fun onTurnComplete() { turnEnded = true }
    fun onBargeIn() { bargeInActive = true; turnEnded = true }
    fun setTextAvailable(available: Boolean) { textAvailable = available }

    // ══════════════════════════════════════════════════════════════════════
    //  TICK
    // ══════════════════════════════════════════════════════════════════════

    fun tick(dtMs: Long, rms: Float, hasVoice: Boolean) {
        val dt = dtMs.coerceIn(1, 32) / 1000f

        // ── 1. Сглаженная аудио-активность ────────────────────────────────
        val rawActivity = if (hasVoice && rms > VOICE_RMS_THRESHOLD) {
            (rms * 3f).coerceAtMost(1f)
        } else {
            0f
        }
        val actSpeed = if (rawActivity > audioActivity) AUDIO_RISE_SPEED else AUDIO_FALL_SPEED
        audioActivity += (rawActivity - audioActivity) * actSpeed * dt
        audioActivity = audioActivity.coerceIn(0f, 1f)

        // ── 2. Silence tracking (по СГЛАЖЕННЫМ данным!) ───────────────────
        // Используем audioActivity вместо raw hasVoice!
        if (audioActivity > ACTIVITY_VOICE_THR) {
            silenceDurationMs = 0L
        } else {
            silenceDurationMs += dtMs
        }

        // ── 3. Pause depth ────────────────────────────────────────────────
        pauseDepth = when {
            silenceDurationMs < 100L -> 0f
            silenceDurationMs < SHORT_PAUSE_THRESHOLD_MS ->
                (silenceDurationMs - 100f) / (SHORT_PAUSE_THRESHOLD_MS - 100f)
            silenceDurationMs < LONG_PAUSE_THRESHOLD_MS ->
                0.5f + 0.5f * (silenceDurationMs - SHORT_PAUSE_THRESHOLD_MS).toFloat() /
                (LONG_PAUSE_THRESHOLD_MS - SHORT_PAUSE_THRESHOLD_MS)
            else -> 1f
        }.coerceIn(0f, 1f)

        // ── 4. Momentum RISE (по СГЛАЖЕННЫМ данным!) ──────────────────────
        if (audioActivity > ACTIVITY_VOICE_THR) {
            speechMomentum += (1f - speechMomentum) * MOMENTUM_RISE_AUDIO * dt
        }
        if (textAvailable) {
            speechMomentum += (0.7f - speechMomentum).coerceAtLeast(0f) * MOMENTUM_RISE_TEXT * dt
        }

        // ── 5. Momentum DECAY ─────────────────────────────────────────────
        val decayRate = when {
            bargeInActive                                   -> MOMENTUM_DECAY_BARGE_IN
            audioActivity > ACTIVITY_VOICE_THR              -> MOMENTUM_DECAY_SPEAKING  // 0!
            turnEnded && !textAvailable                     -> MOMENTUM_DECAY_TURN_ENDED
            silenceDurationMs > LONG_PAUSE_THRESHOLD_MS     -> MOMENTUM_DECAY_LONG_PAUSE
            else                                             -> MOMENTUM_DECAY_SHORT_PAUSE
        }
        speechMomentum -= decayRate * dt
        speechMomentum = speechMomentum.coerceIn(0f, 1f)
    }

    fun reset() {
        speechMomentum = 0f; audioActivity = 0f
        textAvailable = false; pauseDepth = 0f
        silenceDurationMs = 0L; turnEnded = false; bargeInActive = false
    }
}
