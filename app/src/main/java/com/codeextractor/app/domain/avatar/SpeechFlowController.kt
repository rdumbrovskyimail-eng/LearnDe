package com.codeextractor.app.domain.avatar

/**
 * SpeechFlowController — Супервизор непрерывности речи аватара.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ФИЛОСОФИЯ: КОНЦА НЕ СУЩЕСТВУЕТ. ВСЁ — ЦИКЛ.
 * ═══════════════════════════════════════════════════════════════════
 *
 * Пауза при дикции (запятая, вдох)    → speechMomentum = 0.85
 * Конец предложения (точка)            → speechMomentum = 0.70
 * Длинная пауза (Gemini думает)        → speechMomentum медленно → 0.30
 * РЕАЛЬНЫЙ конец (TurnComplete + 2сек) → speechMomentum → 0.0
 *
 * speechMomentum НИКОГДА не падает мгновенно. Минимальный decay:
 *   от 1.0 до 0.0 занимает ~3 секунды.
 *   Это значит: аватар ВСЕГДА доигрывает последнюю позу рта.
 *
 * ВХОДЫ (обновляются извне):
 *   onAudioChunk()     — пришёл PCM от Gemini
 *   onTextChunk()      — пришёл текст от Gemini
 *   onTurnComplete()   — Gemini закончил говорить
 *   onBargeIn()        — пользователь перебил
 *
 * ВЫХОДЫ (читаются каждый кадр):
 *   speechMomentum     — [0..1] общая «инерция» речи
 *   isInSpeechFlow     — true пока speechMomentum > 0.05
 *   audioActivity      — [0..1] мгновенная аудио-активность (сглаженная)
 *   textAvailable      — есть ли текст в ленте
 *   pauseDepth         — [0..1] глубина текущей паузы (0=голос, 1=долгое молчание)
 *
 * Zero-allocation. All fields are primitives.
 */
class SpeechFlowController {

    companion object {
        // ── Momentum decay rates (per second) ─────────────────────────────
        // Чем меньше — тем дольше аватар «помнит» что говорил
        private const val MOMENTUM_DECAY_SPEAKING    = 0.0f    // не падает пока есть голос
        private const val MOMENTUM_DECAY_SHORT_PAUSE = 0.25f   // ~4 сек от 1 до 0
        private const val MOMENTUM_DECAY_LONG_PAUSE  = 0.40f   // ~2.5 сек
        private const val MOMENTUM_DECAY_TURN_ENDED  = 0.55f   // ~1.8 сек
        private const val MOMENTUM_DECAY_BARGE_IN    = 8.0f    // мгновенно (125мс)

        // ── Momentum rise ─────────────────────────────────────────────────
        private const val MOMENTUM_RISE_AUDIO        = 6.0f    // мгновенный рост при голосе
        private const val MOMENTUM_RISE_TEXT          = 3.0f    // рост при новом тексте

        // ── Audio activity smoothing ──────────────────────────────────────
        private const val AUDIO_RISE_SPEED           = 12.0f   // мгновенная реакция
        private const val AUDIO_FALL_SPEED           = 2.0f    // медленное угасание

        // ── Pause classification ──────────────────────────────────────────
        private const val SHORT_PAUSE_THRESHOLD_MS   = 600L    // < 600мс = вдох
        private const val LONG_PAUSE_THRESHOLD_MS    = 1500L   // > 1500мс = думает

        // ── Voice detection ───────────────────────────────────────────────
        private const val VOICE_RMS_THRESHOLD        = 0.022f
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OUTPUTS (read every frame by mapper/pacer)
    // ══════════════════════════════════════════════════════════════════════

    /** Инерция речи [0..1]. Пока > 0.05, аватар считается «в речи» */
    var speechMomentum: Float = 0f; private set

    /** true пока speechMomentum > 0.05 */
    val isInSpeechFlow: Boolean get() = speechMomentum > 0.05f

    /** Мгновенная аудио-активность [0..1], сглаженная */
    var audioActivity: Float = 0f; private set

    /** Есть текст для проигрывания */
    var textAvailable: Boolean = false; private set

    /** Глубина паузы [0..1]: 0=голос активен, 0.5=короткая пауза, 1=долгое молчание */
    var pauseDepth: Float = 0f; private set

    // ══════════════════════════════════════════════════════════════════════
    //  INTERNAL STATE
    // ══════════════════════════════════════════════════════════════════════

    private var silenceDurationMs: Long = 0L
    private var turnEnded: Boolean = false
    private var bargeInActive: Boolean = false
    private var lastAudioRms: Float = 0f
    private var totalAudioReceivedMs: Long = 0L
    private var totalTextChunks: Int = 0

    // ══════════════════════════════════════════════════════════════════════
    //  INPUT EVENTS
    // ══════════════════════════════════════════════════════════════════════

    /** Пришёл PCM-чанк от Gemini */
    fun onAudioChunk(pcmBytes: Int, sampleRate: Int = 24_000) {
        val durationMs = (pcmBytes / 2 * 1000L) / sampleRate
        totalAudioReceivedMs += durationMs
        bargeInActive = false
        turnEnded = false  // если пришёл новый аудио после TurnComplete — отменяем конец
    }

    /** Пришёл текстовый чанк от Gemini */
    fun onTextChunk() {
        totalTextChunks++
        bargeInActive = false
        turnEnded = false
    }

    /** Gemini сказал TurnComplete / GenerationComplete */
    fun onTurnComplete() {
        turnEnded = true
    }

    /** Пользователь перебил */
    fun onBargeIn() {
        bargeInActive = true
        turnEnded = true
    }

    /** Обновить наличие текста в ленте */
    fun setTextAvailable(available: Boolean) {
        textAvailable = available
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TICK (каждый кадр)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Обновляет все выходные поля. Вызывается ПЕРВЫМ в каждом кадре.
     *
     * @param dtMs    delta time в мс
     * @param rms     текущий RMS из AudioFeatures
     * @param hasVoice текущий hasVoice из AudioFeatures
     */
    fun tick(dtMs: Long, rms: Float, hasVoice: Boolean) {
        val dt = dtMs.coerceIn(1, 32) / 1000f

        // ── Audio activity (сглаженная) ───────────────────────────────────
        val rawActivity = if (hasVoice && rms > VOICE_RMS_THRESHOLD) {
            (rms * 3f).coerceAtMost(1f)
        } else {
            0f
        }

        val actSpeed = if (rawActivity > audioActivity) AUDIO_RISE_SPEED else AUDIO_FALL_SPEED
        audioActivity += (rawActivity - audioActivity) * actSpeed * dt
        audioActivity = audioActivity.coerceIn(0f, 1f)

        // ── Silence tracking ──────────────────────────────────────────────
        if (hasVoice && rms > VOICE_RMS_THRESHOLD) {
            silenceDurationMs = 0L
        } else {
            silenceDurationMs += dtMs
        }

        // ── Pause depth [0..1] ────────────────────────────────────────────
        pauseDepth = when {
            silenceDurationMs < 100L                    -> 0f
            silenceDurationMs < SHORT_PAUSE_THRESHOLD_MS -> (silenceDurationMs - 100f) / (SHORT_PAUSE_THRESHOLD_MS - 100f)
            silenceDurationMs < LONG_PAUSE_THRESHOLD_MS  -> 0.5f + 0.5f * (silenceDurationMs - SHORT_PAUSE_THRESHOLD_MS).toFloat() / (LONG_PAUSE_THRESHOLD_MS - SHORT_PAUSE_THRESHOLD_MS)
            else -> 1f
        }.coerceIn(0f, 1f)

        // ── Momentum update ───────────────────────────────────────────────

        // РОСТ: голос или текст поднимают momentum
        if (hasVoice && rms > VOICE_RMS_THRESHOLD) {
            speechMomentum += (1f - speechMomentum) * MOMENTUM_RISE_AUDIO * dt
        }
        // Текст тоже поддерживает momentum (даже без голоса)
        if (textAvailable && !turnEnded) {
            speechMomentum += (0.7f - speechMomentum).coerceAtLeast(0f) * MOMENTUM_RISE_TEXT * dt
        }

        // DECAY: выбираем скорость в зависимости от состояния
        val decayRate = when {
            bargeInActive                               -> MOMENTUM_DECAY_BARGE_IN
            hasVoice && rms > VOICE_RMS_THRESHOLD       -> MOMENTUM_DECAY_SPEAKING
            turnEnded && !textAvailable                 -> MOMENTUM_DECAY_TURN_ENDED
            silenceDurationMs > LONG_PAUSE_THRESHOLD_MS -> MOMENTUM_DECAY_LONG_PAUSE
            else                                         -> MOMENTUM_DECAY_SHORT_PAUSE
        }

        speechMomentum -= decayRate * dt
        speechMomentum = speechMomentum.coerceIn(0f, 1f)

        lastAudioRms = rms
    }

    fun reset() {
        speechMomentum = 0f
        audioActivity = 0f
        textAvailable = false
        pauseDepth = 0f
        silenceDurationMs = 0L
        turnEnded = false
        bargeInActive = false
        lastAudioRms = 0f
        totalAudioReceivedMs = 0L
        totalTextChunks = 0
    }
}
