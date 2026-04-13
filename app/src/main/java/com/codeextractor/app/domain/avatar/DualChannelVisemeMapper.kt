package com.codeextractor.app.domain.avatar

import kotlin.math.sin

/**
 * DualChannelVisemeMapper — Phoneme-Gated Audio-Modulated Viseme System
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ТРОЙНАЯ АРХИТЕКТУРА: GATE → TARGET → MODULATE
 * ═══════════════════════════════════════════════════════════════════
 *
 * GATE (из текста → VisemeGroup):
 *   Железные границы для каждого blendshape.
 *   При BILABIAL: jawOpen ∈ [0.02..0.10], аудио НЕ МОЖЕТ вывести выше.
 *   Это УБИЙЦА рыбьего рта.
 *
 * TARGET (из текста → PhonemeProfile):
 *   Точная поза: jawOpen, lipRound, lipSpread, lipClose, tongueUp, teethClose.
 *   Ко-артикуляция: target интерполируется с nextTarget по progress.
 *
 * MODULATE (из аудио → AudioFeatures):
 *   RMS → интенсивность. SpectralFlux → скорость. Pitch → эмоции.
 *
 * FALLBACK:
 *   Если textConfidence < 0.3, система деградирует к audio-only.
 *   Аватар ВСЕГДА двигает ртом, даже при лагах WebSocket.
 *
 * ZERO-ALLOCATION: единственный FloatArray, все состояния — примитивы.
 */
class DualChannelVisemeMapper {

    private val weights = FloatArray(ARKit.COUNT)

    // ── Neuro-Latent Asymmetry ────────────────────────────────────────────
    private var neuroPhase = 0f
    private var asymL = 1f
    private var asymR = 1f

    // ── Audio-only fallback ───────────────────────────────────────────────
    private var vAA = 0f; private var vEE = 0f; private var vOO = 0f

    companion object {
        private const val VOWEL_DECAY = 0.82f
        private const val TEXT_CONFIDENCE_THR = 0.3f

        // ── Gate ranges: [min, max] для JawOpen ──────────────────────────
        private val JAW_GATE = mapOf(
            VisemeGroup.SILENCE     to floatArrayOf(0.00f, 0.03f),
            VisemeGroup.BILABIAL    to floatArrayOf(0.02f, 0.10f),
            VisemeGroup.LABIODENTAL to floatArrayOf(0.04f, 0.14f),
            VisemeGroup.DENTAL_ALV  to floatArrayOf(0.06f, 0.20f),
            VisemeGroup.PALATAL     to floatArrayOf(0.05f, 0.16f),
            VisemeGroup.VELAR       to floatArrayOf(0.08f, 0.24f),
            VisemeGroup.VOWEL_AA    to floatArrayOf(0.18f, 0.44f),
            VisemeGroup.VOWEL_EE    to floatArrayOf(0.06f, 0.18f),
            VisemeGroup.VOWEL_OO    to floatArrayOf(0.10f, 0.28f),
        )

        private const val ASYM_FREQ = 2.5f
        private const val ASYM_PHASE_R = 0.83f
        private const val ASYM_AMP = 0.05f
        private const val ASYM_BASE = 0.95f
        private const val ASYM_DT = 0.016f
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    fun map(audio: AudioFeatures, ling: LinguisticState, emotion: EmotionalProsody): FloatArray {
        weights.fill(0f)
        updateAsymmetry()

        if (!audio.hasVoice && audio.rms < 0.018f &&
            ling.currentGate == VisemeGroup.SILENCE && ling.textConfidence < 0.1f) {
            applyEmotions(emotion, audio, ling)
            clampAll()
            return weights
        }

        val rms = audio.rms.coerceAtMost(0.92f)

        if (ling.textConfidence >= TEXT_CONFIDENCE_THR) {
            applyTextGuided(audio, ling, rms)
        } else {
            applyAudioFallback(audio, rms)
        }

        applyEmotions(emotion, audio, ling)
        clampAll()
        return weights
    }

    fun reset() {
        weights.fill(0f); neuroPhase = 0f; asymL = 1f; asymR = 1f
        vAA = 0f; vEE = 0f; vOO = 0f
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEXT-GUIDED PATH
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyTextGuided(audio: AudioFeatures, ling: LinguisticState, rms: Float) {
        val gate = ling.currentGate
        val prog = ling.progress

        // ── GATED JAW ─────────────────────────────────────────────────────
        val jr = JAW_GATE[gate] ?: JAW_GATE[VisemeGroup.SILENCE]!!
        val jawTarget = lerp(ling.jawOpen, ling.nextJawOpen, prog * 0.3f)
        val jawMod = jr[0] + (jawTarget * rms * 3f).coerceIn(0f, jr[1] - jr[0])
        weights[ARKit.JawOpen] = jawMod

        // ── АРТИКУЛЯЦИЯ ПО GATE ───────────────────────────────────────────
        when (gate) {
            VisemeGroup.BILABIAL    -> bilabial(ling, audio, rms)
            VisemeGroup.LABIODENTAL -> labiodental(ling, rms)
            VisemeGroup.DENTAL_ALV  -> dental(ling, rms)
            VisemeGroup.PALATAL     -> palatal(ling, rms)
            VisemeGroup.VELAR       -> velar(ling, rms)
            VisemeGroup.VOWEL_AA    -> vowelAA(ling, rms)
            VisemeGroup.VOWEL_EE    -> vowelEE(ling, rms)
            VisemeGroup.VOWEL_OO    -> vowelOO(ling, rms)
            VisemeGroup.SILENCE     -> {}
        }

        // ── КО-АРТИКУЛЯЦИЯ с будущей фонемой ──────────────────────────────
        if (prog > 0.6f && ling.nextGate != gate) {
            val blend = (prog - 0.6f) / 0.4f
            coarticulate(ling, blend * 0.35f, rms)
        }
    }

    // ── Артикуляционные стратегии ─────────────────────────────────────────

    private fun bilabial(ling: LinguisticState, audio: AudioFeatures, rms: Float) {
        val i = (rms * 2.5f + 0.4f).coerceAtMost(1f)
        val seal = ling.lipClose * i
        val jawComp = weights[ARKit.JawOpen] * 0.6f

        maxW(ARKit.MouthClose,      seal * 0.95f + jawComp)
        maxW(ARKit.MouthPressLeft,  seal * 0.60f * asymL)
        maxW(ARKit.MouthPressRight, seal * 0.60f * asymR)
        maxW(ARKit.MouthShrugLower, seal * 0.35f)
        maxW(ARKit.MouthShrugUpper, seal * 0.25f)
        if (ling.lipClose > 0.65f && ling.tongueUp < 0.1f) {
            maxW(ARKit.MouthPucker, seal * 0.18f)
        }
    }

    private fun labiodental(ling: LinguisticState, rms: Float) {
        val i = (rms * 2f + 0.3f).coerceAtMost(1f)
        maxW(ARKit.MouthRollLower,    i * 0.85f)
        maxW(ARKit.MouthUpperUpLeft,  i * 0.40f)
        maxW(ARKit.MouthUpperUpRight, i * 0.40f)
        maxW(ARKit.MouthClose,        i * 0.45f)
        maxW(ARKit.MouthShrugUpper,   i * 0.15f)
    }

    private fun dental(ling: LinguisticState, rms: Float) {
        val i = (rms * 2f + 0.3f).coerceAtMost(1f)
        maxW(ARKit.MouthShrugLower,   ling.tongueUp * i * 0.55f)
        maxW(ARKit.MouthStretchLeft,  ling.lipSpread * i * 0.40f * asymL)
        maxW(ARKit.MouthStretchRight, ling.lipSpread * i * 0.40f * asymR)
        if (ling.teethClose > 0.3f) {
            maxW(ARKit.MouthClose,       ling.teethClose * i * 0.50f)
            maxW(ARKit.MouthDimpleLeft,  i * 0.14f)
            maxW(ARKit.MouthDimpleRight, i * 0.14f)
        }
    }

    private fun palatal(ling: LinguisticState, rms: Float) {
        val i = (rms * 2f + 0.3f).coerceAtMost(1f)
        maxW(ARKit.MouthPucker,      ling.lipRound * i * 0.55f)
        maxW(ARKit.MouthFunnel,      ling.lipRound * i * 0.40f)
        maxW(ARKit.MouthShrugUpper,  ling.tongueUp * i * 0.30f)
        maxW(ARKit.MouthShrugLower,  ling.tongueUp * i * 0.25f)
        maxW(ARKit.MouthRollLower,   i * 0.12f)
    }

    private fun velar(ling: LinguisticState, rms: Float) {
        val i = (rms * 2f + 0.2f).coerceAtMost(1f)
        maxW(ARKit.MouthStretchLeft,  i * 0.18f * asymL)
        maxW(ARKit.MouthStretchRight, i * 0.18f * asymR)
        maxW(ARKit.MouthUpperUpLeft,  i * 0.10f)
        maxW(ARKit.MouthUpperUpRight, i * 0.10f)
    }

    private fun vowelAA(ling: LinguisticState, rms: Float) {
        val v = (rms * 3f).coerceAtMost(0.94f)
        maxW(ARKit.MouthLowerDownLeft,  v * 0.22f * asymL)
        maxW(ARKit.MouthLowerDownRight, v * 0.22f * asymR)
        maxW(ARKit.MouthUpperUpLeft,    v * 0.08f)
        maxW(ARKit.MouthUpperUpRight,   v * 0.08f)
        maxW(ARKit.MouthStretchLeft,    v * 0.08f * asymL)
        maxW(ARKit.MouthStretchRight,   v * 0.08f * asymR)
    }

    private fun vowelEE(ling: LinguisticState, rms: Float) {
        val v = (rms * 2.5f).coerceAtMost(0.88f)
        maxW(ARKit.MouthStretchLeft,  ling.lipSpread * v * 0.45f * asymL)
        maxW(ARKit.MouthStretchRight, ling.lipSpread * v * 0.45f * asymR)
        maxW(ARKit.MouthSmileLeft,    v * 0.10f * asymL)
        maxW(ARKit.MouthSmileRight,   v * 0.10f * asymR)
        maxW(ARKit.MouthDimpleLeft,   v * 0.06f)
        maxW(ARKit.MouthDimpleRight,  v * 0.06f)
        maxW(ARKit.MouthShrugLower,   ling.tongueUp * v * 0.15f)
    }

    private fun vowelOO(ling: LinguisticState, rms: Float) {
        val v = (rms * 2.5f).coerceAtMost(0.90f)
        maxW(ARKit.MouthFunnel,      ling.lipRound * v * 0.55f)
        maxW(ARKit.MouthPucker,      ling.lipRound * v * 0.45f)
        maxW(ARKit.MouthPressLeft,   v * 0.12f)
        maxW(ARKit.MouthPressRight,  v * 0.12f)
        maxW(ARKit.MouthRollLower,   v * 0.10f)
        maxW(ARKit.MouthRollUpper,   v * 0.08f)
    }

    private fun coarticulate(ling: LinguisticState, factor: Float, rms: Float) {
        val f = factor * rms * 2f
        val nr = JAW_GATE[ling.nextGate] ?: return
        val nj = (nr[0] + nr[1]) * 0.5f
        weights[ARKit.JawOpen] = lerp(weights[ARKit.JawOpen], nj, f)
        when (ling.nextGate) {
            VisemeGroup.BILABIAL -> maxW(ARKit.MouthClose, f * 0.3f)
            VisemeGroup.VOWEL_OO -> {
                maxW(ARKit.MouthPucker, ling.nextLipRound * f * 0.25f)
                maxW(ARKit.MouthFunnel, ling.nextLipRound * f * 0.20f)
            }
            VisemeGroup.VOWEL_EE -> {
                maxW(ARKit.MouthStretchLeft,  ling.nextLipSpread * f * 0.20f)
                maxW(ARKit.MouthStretchRight, ling.nextLipSpread * f * 0.20f)
            }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AUDIO-ONLY FALLBACK
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyAudioFallback(audio: AudioFeatures, rms: Float) {
        vAA *= VOWEL_DECAY; vEE *= VOWEL_DECAY; vOO *= VOWEL_DECAY
        val lo = audio.energyLow; val mid = audio.energyMid; val hi = audio.energyHigh
        val tot = lo + mid + hi + 0.001f
        val loR = lo / tot; val midR = mid / tot; val hiR = hi / tot

        when {
            loR > 0.46f && hiR < 0.14f -> vOO = maxOf(vOO, lo * 0.50f)
            loR > 0.30f && midR > 0.22f -> vAA = maxOf(vAA, rms * 0.55f)
            midR > 0.36f -> vEE = maxOf(vEE, mid * 0.50f)
        }

        val jaw = (vAA * 0.55f + vOO * 0.32f + vEE * 0.12f + rms * 0.15f).coerceIn(0f, 0.38f)
        weights[ARKit.JawOpen] = jaw

        if (audio.isPlosive || audio.spectralFlux > 0.30f) {
            if (loR > 0.38f) {
                maxW(ARKit.MouthClose, 0.70f)
                maxW(ARKit.MouthPressLeft, 0.40f * asymL)
                maxW(ARKit.MouthPressRight, 0.40f * asymR)
            }
        }
        if (rms > 0.04f) {
            if (vAA > 0.025f) {
                val v = vAA.coerceAtMost(0.94f)
                maxW(ARKit.MouthLowerDownLeft, v * 0.18f * asymL)
                maxW(ARKit.MouthLowerDownRight, v * 0.18f * asymR)
            }
            if (vEE > 0.025f) {
                val v = vEE.coerceAtMost(0.88f)
                maxW(ARKit.MouthStretchLeft, v * 0.30f * asymL)
                maxW(ARKit.MouthStretchRight, v * 0.30f * asymR)
            }
            if (vOO > 0.025f) {
                val v = vOO.coerceAtMost(0.90f)
                maxW(ARKit.MouthFunnel, v * 0.40f)
                maxW(ARKit.MouthPucker, v * 0.30f)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ЭМОЦИИ (с предсказательной пунктуацией)
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyEmotions(emotion: EmotionalProsody, audio: AudioFeatures, ling: LinguisticState) {
        val boost = 1f + audio.pitchVariance * 0.42f
        val rms = audio.rms

        // Позитивная валенция → Duchenne
        if (emotion.valence > 0.08f) {
            val s = (emotion.valence * boost).coerceAtMost(1f)
            val sb = 1f + rms * 0.22f
            maxW(ARKit.MouthSmileLeft, s * 0.64f * sb * asymL)
            maxW(ARKit.MouthSmileRight, s * 0.60f * sb * asymR)
            maxW(ARKit.CheekSquintLeft, s * 0.50f * asymL)
            maxW(ARKit.CheekSquintRight, s * 0.50f * asymR)
            if (s > 0.32f) {
                val e = (s - 0.32f) * 0.55f
                maxW(ARKit.EyeSquintLeft, e * asymL)
                maxW(ARKit.EyeSquintRight, e * asymR)
            }
        }

        // Негативная → frown
        if (emotion.valence < -0.08f) {
            val f = (-emotion.valence * boost).coerceAtMost(1f)
            maxW(ARKit.MouthFrownLeft, f * 0.56f * asymL)
            maxW(ARKit.MouthFrownRight, f * 0.56f * asymR)
            maxW(ARKit.BrowDownLeft, f * 0.66f * asymL)
            maxW(ARKit.BrowDownRight, f * 0.62f * asymR)
            maxW(ARKit.BrowInnerUp, f * 0.36f)
        }

        // Arousal → wide eyes
        if (emotion.arousal > 0.20f) {
            val a = emotion.arousal - 0.20f
            maxW(ARKit.BrowInnerUp, a * 0.52f)
            maxW(ARKit.BrowOuterUpLeft, a * 0.40f * asymL)
            maxW(ARKit.BrowOuterUpRight, a * 0.38f * asymR)
        }

        // Thoughtfulness
        if (emotion.thoughtfulness > 0.15f) {
            val t = emotion.thoughtfulness
            maxW(ARKit.BrowInnerUp, t * 0.38f)
            maxW(ARKit.MouthPressLeft, t * 0.32f * asymL)
            maxW(ARKit.MouthPressRight, t * 0.32f * asymR)
            maxW(ARKit.MouthRollLower, t * 0.18f)
            maxW(ARKit.EyeSquintLeft, t * 0.14f)
            maxW(ARKit.EyeSquintRight, t * 0.14f)
            if (t > 0.4f) maxW(ARKit.MouthRight, (t - 0.4f) * 0.20f)
        }

        // ── Предсказательная пунктуация ───────────────────────────────────
        when (ling.predictedEmotion) {
            LinguisticState.PunctuationType.QUESTION -> {
                val q = (boost * 0.40f).coerceAtMost(0.70f)
                maxW(ARKit.BrowOuterUpLeft, q * asymL)
                maxW(ARKit.BrowOuterUpRight, q * asymR)
                maxW(ARKit.BrowInnerUp, q * 0.50f)
            }
            LinguisticState.PunctuationType.EXCLAMATION -> {
                val e = (boost * 0.35f).coerceAtMost(0.60f)
                maxW(ARKit.BrowDownLeft, e * asymL)
                maxW(ARKit.BrowDownRight, e * asymR)
                maxW(ARKit.CheekSquintLeft, e * asymL)
                maxW(ARKit.CheekSquintRight, e * asymR)
                maxW(ARKit.NoseSneerLeft, e * 0.30f)
                maxW(ARKit.NoseSneerRight, e * 0.30f)
                maxW(ARKit.MouthPressLeft, 0.25f)
                maxW(ARKit.MouthPressRight, 0.25f)
            }
            LinguisticState.PunctuationType.PAUSE -> {
                maxW(ARKit.MouthPressLeft, 0.15f * asymL)
                maxW(ARKit.MouthPressRight, 0.15f * asymR)
            }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════

    private fun maxW(i: Int, v: Float) { weights[i] = maxOf(weights[i], v) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun updateAsymmetry() {
        neuroPhase += ASYM_DT * ASYM_FREQ * (2f * Math.PI.toFloat())
        asymL = ASYM_BASE + sin(neuroPhase) * ASYM_AMP
        asymR = ASYM_BASE + sin(neuroPhase * ASYM_PHASE_R) * ASYM_AMP
    }
    private fun clampAll() { for (i in 0 until ARKit.COUNT) weights[i] = weights[i].coerceIn(0f, 1f) }
}
