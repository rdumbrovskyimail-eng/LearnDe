package com.codeextractor.app.domain.avatar

/**
 * VisemeMapper v2 — 10 визем-классов вместо 4.
 *
 * Визем-классы (по IPA/ARKit маппингу):
 *   SILENCE — рот закрыт
 *   PP      — билабиальные (P, B, M) — губы сжаты
 *   FF      — лабиодентальные (F, V) — нижняя губа к зубам
 *   TH      — дентальные (T, D, θ, ð) — язык к зубам
 *   KG      — велярные (K, G, NG) — задняя часть рта
 *   SS      — сибилянты (S, Z) — зубы почти сомкнуты
 *   SH      — пост-альвеолярные (Sh, Zh, Ch, J) — губы вперёд
 *   AA      — открытые гласные (A, Ah) — широко открыт
 *   EE      — передние гласные (E, I) — растянутые губы
 *   OO      — округлённые гласные (O, U) — губы трубочкой
 *
 * Маппинг делается через спектральное доминирование +
 * соотношения полос + динамику (переходы).
 */
class VisemeMapper {

    private val weights = FloatArray(ARKit.COUNT)

    // Предыдущее состояние для детекции переходов
    private var prevLo = 0f
    private var prevMid = 0f
    private var prevHi = 0f
    private var prevRms = 0f
    private var transientScore = 0f  // быстрые изменения = взрывные/фрикативы

    fun map(features: AudioFeatures, emotion: EmotionalProsody): FloatArray {
        weights.fill(0f)

        if (!features.hasVoice) {
            prevRms = 0f
            return weights
        }

        val rms = features.rms
        val lo = features.energyLow
        val mid = features.energyMid
        val hi = features.energyHigh

        // ─── TRANSIENT DETECTION (взрывные согласные) ───
        val dRms = (rms - prevRms).coerceAtLeast(0f)
        val dHi = (hi - prevHi).coerceAtLeast(0f)
        transientScore = transientScore * 0.6f + (dRms * 4f + dHi * 3f) * 0.4f

        // ─── CLASSIFY VISEME ───
        val total = lo + mid + hi + 0.001f
        val loRatio = lo / total
        val midRatio = mid / total
        val hiRatio = hi / total

        // Взрывные (PP, KG): высокий transient + короткий burst
        if (transientScore > 0.3f && rms > 0.1f) {
            if (loRatio > 0.4f) {
                // PP — билабиальные: губы сжимаются перед взрывом
                applyPP(transientScore.coerceAtMost(1f))
            } else {
                // KG — велярные: задняя артикуляция
                applyKG(transientScore.coerceAtMost(1f))
            }
        }

        // SS — сибилянты: высокочастотный шум, мало амплитуды
        if (hiRatio > 0.45f && hi > 0.15f && rms < 0.4f) {
            applySS(hi)
        }
        // SH — пост-альвеолярные: высокие + немного средних
        else if (hiRatio > 0.3f && midRatio > 0.2f && hi > 0.1f) {
            applySH(hi, mid)
        }

        // FF — лабиодентальные: слабый высокочастотный шум
        if (hiRatio > 0.35f && rms < 0.2f && transientScore < 0.15f) {
            applyFF(hi)
        }

        // TH — дентальные: средние частоты, слабый сигнал
        if (midRatio > 0.4f && rms < 0.25f && hi < 0.1f) {
            applyTH(mid)
        }

        // ─── ГЛАСНЫЕ (доминируют когда transient низкий) ───
        val vowelStrength = (1f - transientScore * 2f).coerceIn(0f, 1f)

        if (vowelStrength > 0.3f) {
            when {
                // OO (O, U): доминирует низ, мало верхов
                loRatio > 0.5f && hiRatio < 0.15f && lo > 0.2f -> {
                    applyOO(lo * vowelStrength)
                }
                // AA (A, Ah): сильный низ + средние, широкий спектр
                loRatio > 0.35f && midRatio > 0.25f -> {
                    applyAA(rms * vowelStrength, lo)
                }
                // EE (E, I): доминируют средние, растянутые губы
                midRatio > 0.4f && mid > lo -> {
                    applyEE(mid * vowelStrength)
                }
                // Default vowel: смесь AA/EE по соотношению
                else -> {
                    val aaWeight = loRatio * vowelStrength * 0.5f
                    val eeWeight = midRatio * vowelStrength * 0.5f
                    applyAA(aaWeight, lo * 0.5f)
                    applyEE(eeWeight * 0.7f)
                }
            }
        }

        // ─── JAW (всегда пропорционален RMS) ───
        val jawBase = (rms * 0.5f + lo * 0.3f).coerceIn(0f, 0.85f)
        weights[ARKit.JawOpen] = maxOf(weights[ARKit.JawOpen], jawBase)

        // ─── EMOTION OVERLAY (без изменений от v1) ───
        applyEmotions(emotion)

        // ─── CLAMP ───
        for (i in 0 until ARKit.COUNT) weights[i] = weights[i].coerceIn(0f, 1f)

        // Save state
        prevLo = lo; prevMid = mid; prevHi = hi; prevRms = rms

        return weights
    }

    // ─── ВИЗЕМ-АППЛИКАТОРЫ ───

    private fun applyPP(strength: Float) {
        // Билабиальные: губы сжаты, потом раскрытие
        weights[ARKit.MouthClose] = maxOf(weights[ARKit.MouthClose], strength * 0.8f)
        weights[ARKit.MouthPressLeft] = maxOf(weights[ARKit.MouthPressLeft], strength * 0.6f)
        weights[ARKit.MouthPressRight] = maxOf(weights[ARKit.MouthPressRight], strength * 0.6f)
        weights[ARKit.MouthPucker] = maxOf(weights[ARKit.MouthPucker], strength * 0.3f)
        weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * (1f - strength * 0.7f)
    }

    private fun applyKG(strength: Float) {
        // Велярные: задняя артикуляция, рот приоткрыт, горло напряжено
        weights[ARKit.JawOpen] = maxOf(weights[ARKit.JawOpen], strength * 0.35f)
        weights[ARKit.MouthStretchLeft] = maxOf(weights[ARKit.MouthStretchLeft], strength * 0.2f)
        weights[ARKit.MouthStretchRight] = maxOf(weights[ARKit.MouthStretchRight], strength * 0.2f)
        weights[ARKit.MouthUpperUpLeft] = maxOf(weights[ARKit.MouthUpperUpLeft], strength * 0.15f)
        weights[ARKit.MouthUpperUpRight] = maxOf(weights[ARKit.MouthUpperUpRight], strength * 0.15f)
    }

    private fun applySS(hi: Float) {
        // Сибилянты: зубы почти сомкнуты, губы слегка растянуты
        val s = hi.coerceAtMost(0.8f)
        weights[ARKit.MouthClose] = maxOf(weights[ARKit.MouthClose], s * 0.7f)
        weights[ARKit.MouthStretchLeft] = maxOf(weights[ARKit.MouthStretchLeft], s * 0.4f)
        weights[ARKit.MouthStretchRight] = maxOf(weights[ARKit.MouthStretchRight], s * 0.4f)
        weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.2f
        weights[ARKit.MouthDimpleLeft] = maxOf(weights[ARKit.MouthDimpleLeft], s * 0.2f)
        weights[ARKit.MouthDimpleRight] = maxOf(weights[ARKit.MouthDimpleRight], s * 0.2f)
    }

    private fun applySH(hi: Float, mid: Float) {
        // Пост-альвеолярные: губы вперёд (трубочкой), округлены
        val s = (hi * 0.6f + mid * 0.4f).coerceAtMost(0.8f)
        weights[ARKit.MouthPucker] = maxOf(weights[ARKit.MouthPucker], s * 0.65f)
        weights[ARKit.MouthFunnel] = maxOf(weights[ARKit.MouthFunnel], s * 0.5f)
        weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.3f
        weights[ARKit.MouthShrugUpper] = maxOf(weights[ARKit.MouthShrugUpper], s * 0.2f)
    }

    private fun applyFF(hi: Float) {
        // Лабиодентальные: нижняя губа поджата к верхним зубам
        val s = hi.coerceAtMost(0.7f)
        weights[ARKit.MouthRollLower] = maxOf(weights[ARKit.MouthRollLower], s * 0.7f)
        weights[ARKit.MouthUpperUpLeft] = maxOf(weights[ARKit.MouthUpperUpLeft], s * 0.25f)
        weights[ARKit.MouthUpperUpRight] = maxOf(weights[ARKit.MouthUpperUpRight], s * 0.25f)
        weights[ARKit.MouthClose] = maxOf(weights[ARKit.MouthClose], s * 0.3f)
        weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.4f
    }

    private fun applyTH(mid: Float) {
        // Дентальные: язык к зубам, рот слегка приоткрыт
        val s = mid.coerceAtMost(0.6f)
        weights[ARKit.JawOpen] = maxOf(weights[ARKit.JawOpen], s * 0.2f)
        weights[ARKit.MouthShrugLower] = maxOf(weights[ARKit.MouthShrugLower], s * 0.4f)
        weights[ARKit.MouthLowerDownLeft] = maxOf(weights[ARKit.MouthLowerDownLeft], s * 0.15f)
        weights[ARKit.MouthLowerDownRight] = maxOf(weights[ARKit.MouthLowerDownRight], s * 0.15f)
    }

    private fun applyAA(strength: Float, lo: Float) {
        // Открытые гласные: широко открытый рот
        val s = strength.coerceAtMost(0.9f)
        weights[ARKit.JawOpen] = maxOf(weights[ARKit.JawOpen], s * 0.8f)
        weights[ARKit.MouthLowerDownLeft] = maxOf(weights[ARKit.MouthLowerDownLeft], s * 0.4f)
        weights[ARKit.MouthLowerDownRight] = maxOf(weights[ARKit.MouthLowerDownRight], s * 0.4f)
        weights[ARKit.MouthUpperUpLeft] = maxOf(weights[ARKit.MouthUpperUpLeft], s * 0.15f)
        weights[ARKit.MouthUpperUpRight] = maxOf(weights[ARKit.MouthUpperUpRight], s * 0.15f)
        // Лёгкое округление если есть бас
        weights[ARKit.MouthFunnel] = maxOf(weights[ARKit.MouthFunnel], lo * 0.15f)
    }

    private fun applyEE(strength: Float) {
        // Передние гласные: растянутые губы, малое открытие
        val s = strength.coerceAtMost(0.8f)
        weights[ARKit.MouthStretchLeft] = maxOf(weights[ARKit.MouthStretchLeft], s * 0.6f)
        weights[ARKit.MouthStretchRight] = maxOf(weights[ARKit.MouthStretchRight], s * 0.6f)
        weights[ARKit.MouthSmileLeft] = maxOf(weights[ARKit.MouthSmileLeft], s * 0.15f)
        weights[ARKit.MouthSmileRight] = maxOf(weights[ARKit.MouthSmileRight], s * 0.15f)
        weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.5f
        weights[ARKit.MouthDimpleLeft] = maxOf(weights[ARKit.MouthDimpleLeft], s * 0.15f)
        weights[ARKit.MouthDimpleRight] = maxOf(weights[ARKit.MouthDimpleRight], s * 0.15f)
    }

    private fun applyOO(strength: Float) {
        // Округлённые гласные: губы трубочкой
        val s = strength.coerceAtMost(0.85f)
        weights[ARKit.MouthFunnel] = maxOf(weights[ARKit.MouthFunnel], s * 0.7f)
        weights[ARKit.MouthPucker] = maxOf(weights[ARKit.MouthPucker], s * 0.55f)
        weights[ARKit.JawOpen] = maxOf(weights[ARKit.JawOpen], s * 0.35f)
        // Сужение уголков рта
        weights[ARKit.MouthPressLeft] = maxOf(weights[ARKit.MouthPressLeft], s * 0.2f)
        weights[ARKit.MouthPressRight] = maxOf(weights[ARKit.MouthPressRight], s * 0.2f)
    }

    // ─── ЭМОЦИИ (расширенный оверлей) ───

    private fun applyEmotions(emotion: EmotionalProsody) {
        // Positive valence → smile + cheek raise + subtle eye squint
        if (emotion.valence > 0.1f) {
            val s = emotion.valence
            weights[ARKit.MouthSmileLeft] = maxOf(weights[ARKit.MouthSmileLeft], s * 0.6f)
            weights[ARKit.MouthSmileRight] = maxOf(weights[ARKit.MouthSmileRight], s * 0.57f)
            weights[ARKit.CheekSquintLeft] = maxOf(weights[ARKit.CheekSquintLeft], s * 0.45f)
            weights[ARKit.CheekSquintRight] = maxOf(weights[ARKit.CheekSquintRight], s * 0.45f)
            // Duchenne marker: глаза прищуриваются при искренней улыбке
            if (s > 0.4f) {
                weights[ARKit.EyeSquintLeft] = maxOf(weights[ARKit.EyeSquintLeft], (s - 0.4f) * 0.5f)
                weights[ARKit.EyeSquintRight] = maxOf(weights[ARKit.EyeSquintRight], (s - 0.4f) * 0.5f)
            }
            // Сильная радость → щёки надуваются слегка
            if (s > 0.6f) {
                weights[ARKit.CheekPuff] = maxOf(weights[ARKit.CheekPuff], (s - 0.6f) * 0.3f)
            }
        }

        // Negative valence → frown + brow complex
        if (emotion.valence < -0.1f) {
            val f = -emotion.valence
            weights[ARKit.MouthFrownLeft] = maxOf(weights[ARKit.MouthFrownLeft], f * 0.5f)
            weights[ARKit.MouthFrownRight] = maxOf(weights[ARKit.MouthFrownRight], f * 0.5f)
            weights[ARKit.BrowDownLeft] = maxOf(weights[ARKit.BrowDownLeft], f * 0.6f)
            weights[ARKit.BrowDownRight] = maxOf(weights[ARKit.BrowDownRight], f * 0.55f)
            weights[ARKit.BrowInnerUp] = maxOf(weights[ARKit.BrowInnerUp], f * 0.35f) // "worried" brow
            weights[ARKit.NoseSneerLeft] = maxOf(weights[ARKit.NoseSneerLeft], f * 0.25f)
            weights[ARKit.NoseSneerRight] = maxOf(weights[ARKit.NoseSneerRight], f * 0.25f)
            // Верхние веки слегка опускаются
            weights[ARKit.EyeSquintLeft] = maxOf(weights[ARKit.EyeSquintLeft], f * 0.2f)
            weights[ARKit.EyeSquintRight] = maxOf(weights[ARKit.EyeSquintRight], f * 0.2f)
        }

        // Arousal → wide eyes + brow raise
        if (emotion.arousal > 0.25f) {
            val a = emotion.arousal - 0.25f
            weights[ARKit.BrowInnerUp] = maxOf(weights[ARKit.BrowInnerUp], a * 0.5f)
            weights[ARKit.BrowOuterUpLeft] = maxOf(weights[ARKit.BrowOuterUpLeft], a * 0.35f)
            weights[ARKit.BrowOuterUpRight] = maxOf(weights[ARKit.BrowOuterUpRight], a * 0.35f)
            // Глаза шире при высоком возбуждении
            if (emotion.arousal > 0.6f) {
                val w = (emotion.arousal - 0.6f) * 0.4f
                weights[ARKit.EyeWideLeft] = maxOf(weights[ARKit.EyeWideLeft], w)
                weights[ARKit.EyeWideRight] = maxOf(weights[ARKit.EyeWideRight], w)
            }
        }

        // Thoughtfulness → brow knit + mouth press + slight squint
        if (emotion.thoughtfulness > 0.2f) {
            val t = emotion.thoughtfulness
            weights[ARKit.BrowInnerUp] = maxOf(weights[ARKit.BrowInnerUp], t * 0.35f)
            weights[ARKit.BrowDownLeft] = maxOf(weights[ARKit.BrowDownLeft], t * 0.15f)
            weights[ARKit.BrowDownRight] = maxOf(weights[ARKit.BrowDownRight], t * 0.15f)
            weights[ARKit.MouthPressLeft] = maxOf(weights[ARKit.MouthPressLeft], t * 0.35f)
            weights[ARKit.MouthPressRight] = maxOf(weights[ARKit.MouthPressRight], t * 0.35f)
            weights[ARKit.MouthRollLower] = maxOf(weights[ARKit.MouthRollLower], t * 0.2f)
            weights[ARKit.EyeSquintLeft] = maxOf(weights[ARKit.EyeSquintLeft], t * 0.15f)
            weights[ARKit.EyeSquintRight] = maxOf(weights[ARKit.EyeSquintRight], t * 0.15f)
        }
    }
}
