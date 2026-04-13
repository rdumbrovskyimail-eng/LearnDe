package com.codeextractor.app.domain.avatar

/**
 * VisemeMapper v4 — объединение лучших подходов.
 *
 * Архитектура:
 * 1. CONSONANT PRIORITY (Gemini): Взрывные и фрикативы ПОДАВЛЯЮТ гласные через vowelSuppression.
 *    Это решает проблему "рот открыт на громком П" — губы принудительно сжимаются.
 *
 * 2. HOLD TIMERS (Gemini): plosiveHold и fricativeHold создают физиологически
 *    корректную длительность артикуляции (взрывной ~20ms, фрикатив ~80ms).
 *
 * 3. ZCR ROUTING (Gemini): Zero-Crossing Rate различает С/З (высокий ZCR)
 *    от Ш/Ж (средний ZCR + средние частоты) от Ф/В (низкий ZCR, мало энергии).
 *
 * 4. SOFT BLENDING (Claude): Визем-аккумуляторы с decay вместо жёсткого if/else.
 *    Переходы между фонемами плавные, но не размазанные.
 *
 * 5. MICRO-ASYMMETRY (Claude): Лёгкая рандомная разница L/R для естественности.
 *
 * 6. PITCH VARIANCE EMOTIONS (Grok): pitchVariance усиливает улыбку при
 *    экспрессивной речи и frown при монотонной.
 */
class VisemeMapper {

    private val weights = FloatArray(ARKit.COUNT)

    // ═══ Consonant hold timers (Gemini) ═══
    private var plosiveHold = 0f      // быстрый decay: взрывные ~2-3 кадра
    private var fricativeHold = 0f    // медленный decay: шипящие длятся дольше

    // ═══ Vowel accumulators (Claude) — мягкое смешивание ═══
    private var vAA = 0f  // открытые гласные (А, Э)
    private var vEE = 0f  // передние гласные (Е, И)
    private var vOO = 0f  // округлённые гласные (О, У)

    // ═══ Previous frame state ═══
    private var prevRms = 0f
    private var prevLo = 0f
    private var prevHi = 0f

    // ═══ Micro-asymmetry (Claude) ═══
    private var asymTimer = 0f
    private var asymL = 1.0f
    private var asymR = 1.0f

    fun map(features: AudioFeatures, emotion: EmotionalProsody): FloatArray {
        weights.fill(0f)

        // Decay vowel accumulators между кадрами
        vAA *= 0.7f; vEE *= 0.7f; vOO *= 0.7f
        updateAsymmetry()

        if (!features.hasVoice && features.zcr < 0.15f && features.rms < 0.02f) {
            plosiveHold = 0f; fricativeHold = 0f
            return weights
        }

        val rms = features.rms.coerceAtMost(0.9f)
        val lo = features.energyLow
        val mid = features.energyMid
        val hi = features.energyHigh
        val total = lo + mid + hi + 0.001f
        val loRatio = lo / total
        val midRatio = mid / total
        val hiRatio = hi / total

        // ══════════════════════════════════════════════════════
        //  PHASE 1: СОГЛАСНЫЕ (PRIORITY — подавляют гласные)
        //  Идея из Gemini: vowelSuppression уменьшается при согласных,
        //  блокируя JawOpen и гласные визембы.
        // ══════════════════════════════════════════════════════
        var vowelSuppression = 1f

        // ── ВЗРЫВНЫЕ (P, B, T, K, G) через isPlosive + spectralFlux ──
        if (features.isPlosive || features.spectralFlux > 0.35f) {
            plosiveHold = 1.0f
        }

        if (plosiveHold > 0f) {
            val p = plosiveHold

            if (loRatio > 0.4f) {
                // Билабиальные (П, Б, М): губы намертво сжаты
                weights[ARKit.MouthClose] = maxW(ARKit.MouthClose, p * 0.9f)
                weights[ARKit.MouthPressLeft] = maxW(ARKit.MouthPressLeft, p * 0.7f * asymL)
                weights[ARKit.MouthPressRight] = maxW(ARKit.MouthPressRight, p * 0.7f * asymR)
                weights[ARKit.MouthPucker] = maxW(ARKit.MouthPucker, p * 0.25f)
                weights[ARKit.MouthRollLower] = maxW(ARKit.MouthRollLower, p * 0.15f)
                weights[ARKit.MouthRollUpper] = maxW(ARKit.MouthRollUpper, p * 0.1f)
            } else {
                // Велярные/альвеолярные (К, Г, Т, Д): задняя артикуляция
                weights[ARKit.JawOpen] = maxW(ARKit.JawOpen, p * 0.35f)
                weights[ARKit.MouthStretchLeft] = maxW(ARKit.MouthStretchLeft, p * 0.22f * asymL)
                weights[ARKit.MouthStretchRight] = maxW(ARKit.MouthStretchRight, p * 0.22f * asymR)
                weights[ARKit.MouthUpperUpLeft] = maxW(ARKit.MouthUpperUpLeft, p * 0.15f)
                weights[ARKit.MouthUpperUpRight] = maxW(ARKit.MouthUpperUpRight, p * 0.15f)
                weights[ARKit.MouthShrugUpper] = maxW(ARKit.MouthShrugUpper, p * 0.12f)
            }

            vowelSuppression -= plosiveHold * 0.85f

            // Быстрый decay: взрывные длятся ~20ms = ~1.2 кадра при 60fps
            plosiveHold = (plosiveHold - 0.22f).coerceAtLeast(0f)
        }

        // ── ФРИКАТИВЫ (С, Ш, Ф, В) через ZCR (Gemini) ──
        if (features.zcr > 0.2f && plosiveHold < 0.1f) {
            fricativeHold = features.zcr
            vowelSuppression = (1f - fricativeHold * 0.8f).coerceAtLeast(0.15f)

            when {
                // "С", "З" — высокий ZCR, много ВЧ: зубы сомкнуты, губы растянуты
                features.zcr > 0.35f && hi > 0.15f -> {
                    val s = (fricativeHold * 1.8f).coerceAtMost(0.9f)
                    weights[ARKit.MouthClose] = maxW(ARKit.MouthClose, s * 0.75f)
                    weights[ARKit.MouthStretchLeft] = maxW(ARKit.MouthStretchLeft, s * 0.5f * asymL)
                    weights[ARKit.MouthStretchRight] = maxW(ARKit.MouthStretchRight, s * 0.5f * asymR)
                    weights[ARKit.MouthDimpleLeft] = maxW(ARKit.MouthDimpleLeft, s * 0.2f)
                    weights[ARKit.MouthDimpleRight] = maxW(ARKit.MouthDimpleRight, s * 0.2f)
                    weights[ARKit.MouthShrugLower] = maxW(ARKit.MouthShrugLower, s * 0.15f)
                    weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.12f
                }
                // "Ш", "Ж" — средний ZCR + средние частоты: губы трубочкой
                mid > 0.12f && features.zcr > 0.25f -> {
                    val s = fricativeHold.coerceAtMost(0.85f)
                    weights[ARKit.MouthPucker] = maxW(ARKit.MouthPucker, s * 0.7f)
                    weights[ARKit.MouthFunnel] = maxW(ARKit.MouthFunnel, s * 0.55f)
                    weights[ARKit.MouthShrugUpper] = maxW(ARKit.MouthShrugUpper, s * 0.25f)
                    weights[ARKit.MouthShrugLower] = maxW(ARKit.MouthShrugLower, s * 0.15f)
                    weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.2f
                }
                // "Ф", "В" — низкий ZCR, мало энергии: нижняя губа к зубам
                rms < 0.25f -> {
                    val s = (fricativeHold * 1.5f).coerceAtMost(0.8f)
                    weights[ARKit.MouthRollLower] = maxW(ARKit.MouthRollLower, s * 0.8f)
                    weights[ARKit.MouthUpperUpLeft] = maxW(ARKit.MouthUpperUpLeft, s * 0.28f)
                    weights[ARKit.MouthUpperUpRight] = maxW(ARKit.MouthUpperUpRight, s * 0.28f)
                    weights[ARKit.MouthClose] = maxW(ARKit.MouthClose, s * 0.35f)
                    weights[ARKit.MouthShrugUpper] = maxW(ARKit.MouthShrugUpper, s * 0.18f)
                    weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.3f
                }
            }
        }

        // Дентальные (TH, Т, Д) — средние частоты, слабый сигнал, низкий ZCR
        if (midRatio > 0.4f && rms < 0.22f && hi < 0.08f && features.zcr < 0.2f) {
            val s = (mid * 1.3f).coerceAtMost(0.6f)
            weights[ARKit.JawOpen] = maxW(ARKit.JawOpen, s * 0.22f)
            weights[ARKit.MouthShrugLower] = maxW(ARKit.MouthShrugLower, s * 0.42f)
            weights[ARKit.MouthLowerDownLeft] = maxW(ARKit.MouthLowerDownLeft, s * 0.18f * asymL)
            weights[ARKit.MouthLowerDownRight] = maxW(ARKit.MouthLowerDownRight, s * 0.18f * asymR)
            vowelSuppression *= 0.6f
        }

        vowelSuppression = vowelSuppression.coerceIn(0f, 1f)

        // ══════════════════════════════════════════════════════
        //  PHASE 2: ГЛАСНЫЕ (только если согласные не подавили)
        // ══════════════════════════════════════════════════════
        if (vowelSuppression > 0.08f && rms > 0.04f) {
            when {
                // "О", "У" — доминируют низкие, рот узкий круглый
                loRatio > 0.48f && hiRatio < 0.13f && lo > 0.12f -> {
                    vOO = maxOf(vOO, lo * vowelSuppression * 1.2f)
                }
                // "А", "Э" — широкий спектр, бас + средние
                loRatio > 0.32f && midRatio > 0.24f && rms > 0.12f -> {
                    vAA = maxOf(vAA, rms * vowelSuppression * 1.3f)
                }
                // "Е", "И" — доминируют средние, растянутые губы
                midRatio > 0.38f && mid > lo -> {
                    vEE = maxOf(vEE, mid * vowelSuppression * 1.2f)
                }
                // Смешанная гласная
                else -> {
                    vAA = maxOf(vAA, loRatio * vowelSuppression * 0.5f)
                    vEE = maxOf(vEE, midRatio * vowelSuppression * 0.45f)
                }
            }

            applyAA(vAA, lo)
            applyEE(vEE)
            applyOO(vOO)

            // Адаптивный JAW (масштабирован vowelSuppression)
            val jawFromRms = rms * 0.5f * vowelSuppression
            val jawFromVowel = maxOf(vAA * 0.65f, vOO * 0.38f, vEE * 0.18f) * vowelSuppression
            val jawTarget = (jawFromRms + jawFromVowel).coerceIn(0f, 0.88f)
            weights[ARKit.JawOpen] = maxOf(weights[ARKit.JawOpen], jawTarget)
        }

        // ══════════════════════════════════════════════════════
        //  PHASE 3: ЭМОЦИИ
        // ══════════════════════════════════════════════════════
        applyEmotions(emotion, features.pitchVariance, rms)

        // Clamp
        for (i in 0 until ARKit.COUNT) weights[i] = weights[i].coerceIn(0f, 1f)

        prevRms = rms; prevLo = lo; prevHi = hi
        return weights
    }

    // ═══════════════════════════════════════════════
    //  VOWEL APPLICATORS (Claude: asymmetry + anatomy)
    // ═══════════════════════════════════════════════

    private fun applyAA(s: Float, lo: Float) {
        if (s < 0.03f) return
        val v = s.coerceAtMost(0.92f)
        weights[ARKit.JawOpen] = maxW(ARKit.JawOpen, v * 0.82f)
        weights[ARKit.MouthLowerDownLeft] = maxW(ARKit.MouthLowerDownLeft, v * 0.42f * asymL)
        weights[ARKit.MouthLowerDownRight] = maxW(ARKit.MouthLowerDownRight, v * 0.42f * asymR)
        weights[ARKit.MouthUpperUpLeft] = maxW(ARKit.MouthUpperUpLeft, v * 0.16f)
        weights[ARKit.MouthUpperUpRight] = maxW(ARKit.MouthUpperUpRight, v * 0.16f)
        weights[ARKit.MouthFunnel] = maxW(ARKit.MouthFunnel, lo * 0.15f)
        weights[ARKit.MouthStretchLeft] = maxW(ARKit.MouthStretchLeft, v * 0.1f * asymL)
        weights[ARKit.MouthStretchRight] = maxW(ARKit.MouthStretchRight, v * 0.1f * asymR)
    }

    private fun applyEE(s: Float) {
        if (s < 0.03f) return
        val v = s.coerceAtMost(0.85f)
        weights[ARKit.MouthStretchLeft] = maxW(ARKit.MouthStretchLeft, v * 0.62f * asymL)
        weights[ARKit.MouthStretchRight] = maxW(ARKit.MouthStretchRight, v * 0.62f * asymR)
        weights[ARKit.MouthSmileLeft] = maxW(ARKit.MouthSmileLeft, v * 0.16f * asymL)
        weights[ARKit.MouthSmileRight] = maxW(ARKit.MouthSmileRight, v * 0.16f * asymR)
        weights[ARKit.MouthDimpleLeft] = maxW(ARKit.MouthDimpleLeft, v * 0.16f)
        weights[ARKit.MouthDimpleRight] = maxW(ARKit.MouthDimpleRight, v * 0.16f)
        weights[ARKit.MouthShrugLower] = maxW(ARKit.MouthShrugLower, v * 0.1f)
        weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.4f
    }

    private fun applyOO(s: Float) {
        if (s < 0.03f) return
        val v = s.coerceAtMost(0.88f)
        weights[ARKit.MouthFunnel] = maxW(ARKit.MouthFunnel, v * 0.72f)
        weights[ARKit.MouthPucker] = maxW(ARKit.MouthPucker, v * 0.58f)
        weights[ARKit.JawOpen] = maxW(ARKit.JawOpen, v * 0.36f)
        weights[ARKit.MouthPressLeft] = maxW(ARKit.MouthPressLeft, v * 0.2f)
        weights[ARKit.MouthPressRight] = maxW(ARKit.MouthPressRight, v * 0.2f)
        weights[ARKit.MouthRollLower] = maxW(ARKit.MouthRollLower, v * 0.1f)
        weights[ARKit.MouthRollUpper] = maxW(ARKit.MouthRollUpper, v * 0.07f)
    }

    // ═══════════════════════════════════════════════
    //  EMOTIONS (Claude + Grok: pitchVariance boost)
    // ═══════════════════════════════════════════════

    private fun applyEmotions(emotion: EmotionalProsody, pitchVar: Float, rms: Float) {
        // Pitch variance boost (Grok): экспрессивная речь усиливает эмоции
        val exprBoost = 1f + pitchVar * 0.4f

        // ── Positive valence → smile + Duchenne ──
        if (emotion.valence > 0.08f) {
            val s = emotion.valence * exprBoost
            val speechBoost = 1f + rms * 0.25f
            weights[ARKit.MouthSmileLeft] = maxW(ARKit.MouthSmileLeft, s * 0.65f * speechBoost * asymL)
            weights[ARKit.MouthSmileRight] = maxW(ARKit.MouthSmileRight, s * 0.62f * speechBoost * asymR)
            weights[ARKit.CheekSquintLeft] = maxW(ARKit.CheekSquintLeft, s * 0.52f * asymL)
            weights[ARKit.CheekSquintRight] = maxW(ARKit.CheekSquintRight, s * 0.52f * asymR)

            if (s > 0.35f) {
                val eye = (s - 0.35f) * 0.6f
                weights[ARKit.EyeSquintLeft] = maxW(ARKit.EyeSquintLeft, eye * asymL)
                weights[ARKit.EyeSquintRight] = maxW(ARKit.EyeSquintRight, eye * asymR)
            }
            if (s > 0.55f) {
                weights[ARKit.CheekPuff] = maxW(ARKit.CheekPuff, (s - 0.55f) * 0.22f)
                weights[ARKit.NoseSneerLeft] = maxW(ARKit.NoseSneerLeft, (s - 0.55f) * 0.12f)
                weights[ARKit.NoseSneerRight] = maxW(ARKit.NoseSneerRight, (s - 0.55f) * 0.12f)
            }
        }

        // ── Negative valence → frown complex ──
        if (emotion.valence < -0.08f) {
            val f = -emotion.valence * exprBoost
            weights[ARKit.MouthFrownLeft] = maxW(ARKit.MouthFrownLeft, f * 0.58f * asymL)
            weights[ARKit.MouthFrownRight] = maxW(ARKit.MouthFrownRight, f * 0.58f * asymR)
            weights[ARKit.BrowDownLeft] = maxW(ARKit.BrowDownLeft, f * 0.68f * asymL)
            weights[ARKit.BrowDownRight] = maxW(ARKit.BrowDownRight, f * 0.63f * asymR)
            weights[ARKit.BrowInnerUp] = maxW(ARKit.BrowInnerUp, f * 0.38f)
            weights[ARKit.NoseSneerLeft] = maxW(ARKit.NoseSneerLeft, f * 0.26f * asymL)
            weights[ARKit.NoseSneerRight] = maxW(ARKit.NoseSneerRight, f * 0.26f * asymR)
            weights[ARKit.EyeSquintLeft] = maxW(ARKit.EyeSquintLeft, f * 0.2f)
            weights[ARKit.EyeSquintRight] = maxW(ARKit.EyeSquintRight, f * 0.2f)
            weights[ARKit.MouthLowerDownLeft] = maxW(ARKit.MouthLowerDownLeft, f * 0.12f)
            weights[ARKit.MouthLowerDownRight] = maxW(ARKit.MouthLowerDownRight, f * 0.12f)
        }

        // ── Arousal → wide eyes + brow raise ──
        if (emotion.arousal > 0.2f) {
            val a = emotion.arousal - 0.2f
            weights[ARKit.BrowInnerUp] = maxW(ARKit.BrowInnerUp, a * 0.55f)
            weights[ARKit.BrowOuterUpLeft] = maxW(ARKit.BrowOuterUpLeft, a * 0.42f * asymL)
            weights[ARKit.BrowOuterUpRight] = maxW(ARKit.BrowOuterUpRight, a * 0.4f * asymR)
            if (emotion.arousal > 0.55f) {
                val w = (emotion.arousal - 0.55f) * 0.45f
                weights[ARKit.EyeWideLeft] = maxW(ARKit.EyeWideLeft, w * asymL)
                weights[ARKit.EyeWideRight] = maxW(ARKit.EyeWideRight, w * asymR)
            }
        }

        // ── Thoughtfulness → сосредоточенность ──
        if (emotion.thoughtfulness > 0.15f) {
            val t = emotion.thoughtfulness
            weights[ARKit.BrowInnerUp] = maxW(ARKit.BrowInnerUp, t * 0.4f)
            weights[ARKit.BrowDownLeft] = maxW(ARKit.BrowDownLeft, t * 0.17f * asymL)
            weights[ARKit.BrowDownRight] = maxW(ARKit.BrowDownRight, t * 0.17f * asymR)
            weights[ARKit.MouthPressLeft] = maxW(ARKit.MouthPressLeft, t * 0.36f * asymL)
            weights[ARKit.MouthPressRight] = maxW(ARKit.MouthPressRight, t * 0.36f * asymR)
            weights[ARKit.MouthRollLower] = maxW(ARKit.MouthRollLower, t * 0.2f)
            weights[ARKit.MouthRollUpper] = maxW(ARKit.MouthRollUpper, t * 0.1f)
            weights[ARKit.EyeSquintLeft] = maxW(ARKit.EyeSquintLeft, t * 0.16f)
            weights[ARKit.EyeSquintRight] = maxW(ARKit.EyeSquintRight, t * 0.16f)
        }
    }

    // ═══ HELPERS ═══

    private fun maxW(idx: Int, value: Float): Float {
        val v = maxOf(weights[idx], value)
        weights[idx] = v
        return v
    }

    private fun updateAsymmetry() {
        asymTimer += 0.016f
        if (asymTimer > 0.2f) {
            asymTimer = 0f
            asymL = 0.97f + (Math.random() * 0.06f).toFloat()
            asymR = 0.97f + (Math.random() * 0.06f).toFloat()
        }
    }
}
