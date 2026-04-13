package com.codeextractor.app.domain.avatar

import kotlin.math.sin

/**
 * VisemeMapper v5 — Soft-Tissue Decoupled Viseme System
 *
 * АРХИТЕКТУРНЫЕ ПРИНЦИПЫ:
 *
 * 1. JAW ANCHORING (Декаплинг челюсти от губ)
 *    Челюсть — это КОСТЬ. Она формирует пространство для гласной НЕЗАВИСИМО
 *    от того, что делают губы. При слоге «ПА»:
 *      - Челюсть уже опускается (предугадывая «А»)
 *      - Губы смыкаются поверх открытой челюсти (накапливая воздух для «П»)
 *      - Затем губы резко расходятся — взрыв
 *    Старый подход (vowelSuppression гасит JawOpen при согласных) давал
 *    эффект «щелкунчика» — рот хлопал на каждом П/Б/К.
 *
 * 2. CONSONANT PRIORITY через LIP SEAL OVERRIDE
 *    Взрывные и фрикативные НЕ подавляют гласные полностью.
 *    Они переопределяют только ГУБНЫЕ blendshapes через максимизацию:
 *      weights[MouthClose] = max(weights[MouthClose], lipSealStrength)
 *    JawOpen при этом остаётся от гласной.
 *
 * 3. SOFT TISSUE COMPENSATOR
 *    При открытой челюсти (от гласной) + активном взрывном:
 *    lips тянутся сильнее чтобы перекрыть зазор.
 *    jawCompensator = JawOpen * 1.15f добавляется к MouthClose.
 *    Это физиологически верно: губная мышца (orbicularis oris) сокращается
 *    сильнее когда надо перекрыть большее расстояние.
 *
 * 4. ZCR ROUTING (три класса фрикативных)
 *    Высокий ZCR + ВЧ  → С/З (зубные щелевые): губы растянуты, зубы сближены
 *    Средний ZCR + СЧ  → Ш/Ж (шипящие): губы трубочкой
 *    Низкий ZCR + слабый сигнал → Ф/В (губно-зубные): нижняя губа к зубам
 *
 * 5. VOWEL ACCUMULATORS с decay
 *    vAA, vEE, vOO накапливаются и плавно убывают между кадрами.
 *    Это даёт ко-артикуляцию на уровне ФОНЕМ (не blendshape):
 *    гласная «А» начинает формироваться до того как фонема стала доминирующей.
 *
 * 6. NEURO-LATENT ASYMMETRY
 *    Левая и правая стороны лица управляются разными полушариями мозга
 *    с микрозадержкой. Моделируется через два несинхронных синуса:
 *      asymL = 0.95 + sin(neuroPhase) * 0.05
 *      asymR = 0.95 + cos(neuroPhase * 0.83) * 0.05
 *    Частота ~2.5 Гц — соответствует theta-ритму речи.
 *    Амплитуда ±5% — незаметна сознательно, но критична для органичности.
 *
 * 7. HERMITE EASE (easeSoft)
 *    Все выходные значения проходят через Hermite smoothstep:
 *    f(t) = t²(3 - 2t)
 *    Это убирает линейные «рывки» при нарастании blendshape.
 *    Применяется к lip seal и взрывным — самым заметным переходам.
 *
 * 8. EMOTION INTEGRATION
 *    Слой эмоций (valence, arousal, thoughtfulness) применяется ПОСЛЕ
 *    фонетического слоя через max() — никогда не мешает речи,
 *    только дополняет незадействованные blendshapes.
 *
 * ZERO-ALLOCATION:
 *    weights — единственный FloatArray, переиспользуется каждый кадр.
 *    Все состояния — Float примитивы.
 */
class VisemeMapper {

    // ── Output buffer ─────────────────────────────────────────────────────
    private val weights = FloatArray(ARKit.COUNT)

    // ── Consonant hold timers ─────────────────────────────────────────────
    private var plosiveHold   = 0f
    private var fricativeHold = 0f

    // ── Vowel accumulators (decay between frames) ─────────────────────────
    private var vAA = 0f   // А / Э — широкий рот
    private var vEE = 0f   // Е / И — растянутые губы
    private var vOO = 0f   // О / У — округлённые губы

    // ── Neuro-Latent Asymmetry ────────────────────────────────────────────
    private var neuroPhase = 0f
    private var asymL      = 1f
    private var asymR      = 1f

    // ── Plosive subtype (bilabial vs velar) ───────────────────────────────
    private var plosiveBilabial = true   // true = П/Б/М, false = К/Г/Т/Д

    companion object {
        // ── Decay ────────────────────────────────────────────────────────
        private const val VOWEL_DECAY      = 0.80f   // per frame decay для аккумуляторов

        // ── Plosive ───────────────────────────────────────────────────────
        private const val PLOSIVE_TRIGGER_FLUX = 0.30f
        private const val PLOSIVE_DECAY        = 0.24f   // ~4 кадра = ~67 мс
        private const val PLOSIVE_HOLD_INIT    = 1.0f

        // ── Fricative ─────────────────────────────────────────────────────
        private const val FRICATIVE_ZCR_HIGH   = 0.33f   // С/З
        private const val FRICATIVE_ZCR_MID    = 0.22f   // Ш/Ж (порог вхождения)
        private const val FRICATIVE_MID_ENERGY = 0.10f   // минимум СЧ для Ш/Ж

        // ── Jaw Anchoring ─────────────────────────────────────────────────
        private const val JAW_FROM_RMS_SCALE   = 0.18f
        private const val JAW_MAX              = 0.38f
        private const val JAW_COMPENSATOR      = 0.60f  // усиление LipSeal при открытой челюсти

        // ── Soft tissue ease ──────────────────────────────────────────────
        // Hermite smoothstep: применяется только к lip seal и plosive
        // для остальных — линейная шкала (computationally cheaper)

        // ── Asymmetry ─────────────────────────────────────────────────────
        private const val ASYM_FREQ    = 2.5f    // Гц (theta-ритм речи)
        private const val ASYM_PHASE_R = 0.83f   // рассинхрон правого полушария
        private const val ASYM_AMP     = 0.05f
        private const val ASYM_BASE    = 0.95f
        private const val ASYM_DT      = 0.016f  // ~60fps

        // ── Emotion thresholds ────────────────────────────────────────────
        private const val VALENCE_POS_THR   = 0.08f
        private const val VALENCE_NEG_THR   = -0.08f
        private const val AROUSAL_THR       = 0.20f
        private const val THOUGHT_THR       = 0.15f
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Маппинг аудиопризнаков + эмоций → ARKit blendshape веса.
     *
     * @param features  аудиопризнаки текущего кадра
     * @param emotion   текущий вектор эмоций
     * @return FloatArray(ARKit.COUNT) — актуален до следующего вызова map()
     */
    fun map(features: AudioFeatures, emotion: EmotionalProsody): FloatArray {
        weights.fill(0f)

        // ── Tick asymmetry (каждый кадр) ─────────────────────────────────
        updateAsymmetry()

        // ── Decay vowel accumulators ──────────────────────────────────────
        vAA *= VOWEL_DECAY; vEE *= VOWEL_DECAY; vOO *= VOWEL_DECAY

        // ── Ранний выход при абсолютной тишине ────────────────────────────
        if (!features.hasVoice && features.zcr < 0.12f && features.rms < 0.018f) {
            plosiveHold   = (plosiveHold   - PLOSIVE_DECAY).coerceAtLeast(0f)
            fricativeHold = (fricativeHold * 0.75f).coerceAtLeast(0f)
            applyEmotions(emotion, features.pitchVariance, 0f)
            clampAll()
            return weights
        }

        val rms   = features.rms.coerceAtMost(0.92f)
        val lo    = features.energyLow
        val mid   = features.energyMid
        val hi    = features.energyHigh
        val total = lo + mid + hi + 0.001f

        val loRatio  = lo  / total
        val midRatio = mid / total
        val hiRatio  = hi  / total

        // ══════════════════════════════════════════════════════════════════
        //  ФАЗА 1: JAW ANCHOR — независимо от согласных
        //  Челюсть подчиняется гласным, не согласным
        // ══════════════════════════════════════════════════════════════════
        accumulateVowels(rms, lo, mid, hi, loRatio, midRatio, hiRatio)
        val jawFromVowel = (vAA * 0.62f + vOO * 0.36f + vEE * 0.14f)
        val jawFromRms   = rms * JAW_FROM_RMS_SCALE
        val jawTarget    = (jawFromVowel + jawFromRms).coerceIn(0f, JAW_MAX)
        weights[ARKit.JawOpen] = jawTarget

        // ══════════════════════════════════════════════════════════════════
        //  ФАЗА 2: ВЗРЫВНЫЕ (PLOSIVES) — lip seal override
        // ══════════════════════════════════════════════════════════════════
        applyPlosives(features, lo, mid, loRatio)

        // ══════════════════════════════════════════════════════════════════
        //  ФАЗА 3: ФРИКАТИВНЫЕ (FRICATIVES)
        // ══════════════════════════════════════════════════════════════════
        applyFricatives(features, mid, hi, rms)

        // ══════════════════════════════════════════════════════════════════
        //  ФАЗА 4: ДЕНТАЛЬНЫЕ (TH, Т, Д)
        // ══════════════════════════════════════════════════════════════════
        applyDentals(features, mid, midRatio, hi, rms)

        // ══════════════════════════════════════════════════════════════════
        //  ФАЗА 5: ГЛАСНЫЕ — применяем к blendshapes
        // ══════════════════════════════════════════════════════════════════
        if (rms > 0.04f) {
            applyAA(vAA, lo)
            applyEE(vEE)
            applyOO(vOO)
        }

        // ══════════════════════════════════════════════════════════════════
        //  ФАЗА 6: ЭМОЦИИ (поверх речи, через max)
        // ══════════════════════════════════════════════════════════════════
        applyEmotions(emotion, features.pitchVariance, rms)

        clampAll()
        return weights
    }

    fun reset() {
        weights.fill(0f)
        plosiveHold = 0f; fricativeHold = 0f
        vAA = 0f; vEE = 0f; vOO = 0f
        neuroPhase = 0f; asymL = 1f; asymR = 1f
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ФАЗА 1: JAW — аккумуляторы гласных
    // ═══════════════════════════════════════════════════════════════════════

    private fun accumulateVowels(
        rms: Float, lo: Float, mid: Float, hi: Float,
        loRatio: Float, midRatio: Float, hiRatio: Float,
    ) {
        when {
            // О / У — доминируют низкие, мало высоких
            loRatio > 0.46f && hiRatio < 0.14f && lo > 0.10f ->
                vOO = maxOf(vOO, lo * 0.55f)

            // А / Э — широкий спектр, бас + средние
            loRatio > 0.30f && midRatio > 0.22f && rms > 0.10f ->
                vAA = maxOf(vAA, rms * 0.60f)

            // Е / И — доминируют средние
            midRatio > 0.36f && mid > lo ->
                vEE = maxOf(vEE, mid * 0.55f)

            // Смешанный / неопределённый
            else -> {
                vAA = maxOf(vAA, loRatio  * 0.25f)
                vEE = maxOf(vEE, midRatio * 0.22f)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ФАЗА 2: PLOSIVES
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyPlosives(
        features: AudioFeatures,
        lo: Float, mid: Float, loRatio: Float,
    ) {
        // Триггер взрывного
        if (features.isPlosive || features.spectralFlux > PLOSIVE_TRIGGER_FLUX) {
            plosiveHold     = PLOSIVE_HOLD_INIT
            plosiveBilabial = loRatio > 0.38f   // низкие → П/Б/М, иначе К/Г/Т
        }

        if (plosiveHold <= 0f) return

        val p    = plosiveHold
        val ease = easeSoft(p)   // Hermite — плавное нарастание/спад

        // Soft Tissue Compensator: чем шире челюсть, тем сильнее смыкание губ
        val jawComp = weights[ARKit.JawOpen] * JAW_COMPENSATOR

        if (plosiveBilabial) {
            // ── П / Б / М: губы смыкаются поверх открытой челюсти ────────
            maxW(ARKit.MouthClose,      ease * 0.85f + jawComp)
            maxW(ARKit.MouthPressLeft,  ease * 0.50f * asymL)
            maxW(ARKit.MouthPressRight, ease * 0.50f * asymR)
            maxW(ARKit.MouthPucker,     ease * 0.22f)
            maxW(ARKit.MouthRollLower,  ease * 0.14f)
            maxW(ARKit.MouthRollUpper,  ease * 0.10f)
        } else {
            // ── К / Г / Т / Д: задняя артикуляция, губы менее задействованы
            maxW(ARKit.MouthStretchLeft,  ease * 0.24f * asymL)
            maxW(ARKit.MouthStretchRight, ease * 0.24f * asymR)
            maxW(ARKit.MouthUpperUpLeft,  ease * 0.16f)
            maxW(ARKit.MouthUpperUpRight, ease * 0.16f)
            maxW(ARKit.MouthShrugUpper,   ease * 0.12f)
            // Челюсть при велярных — небольшой дополнительный открыв
            maxW(ARKit.JawOpen, weights[ARKit.JawOpen] + ease * 0.18f)
        }

        // Декай: ~4 кадра при 60fps ≈ 67 мс
        plosiveHold = (plosiveHold - PLOSIVE_DECAY).coerceAtLeast(0f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ФАЗА 3: FRICATIVES
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyFricatives(
        features: AudioFeatures,
        mid: Float, hi: Float, rms: Float,
    ) {
        // Фрикативы активны только если нет сильного взрывного
        if (features.zcr < FRICATIVE_ZCR_MID || plosiveHold > 0.15f) return

        fricativeHold = features.zcr

        val s = fricativeHold.coerceAtMost(0.92f)

        when {
            // ── С / З: высокий ZCR + много ВЧ ────────────────────────────
            features.zcr > FRICATIVE_ZCR_HIGH && hi > 0.12f -> {
                maxW(ARKit.MouthClose,       s * 0.72f)
                maxW(ARKit.MouthStretchLeft, s * 0.48f * asymL)
                maxW(ARKit.MouthStretchRight,s * 0.48f * asymR)
                maxW(ARKit.MouthDimpleLeft,  s * 0.18f)
                maxW(ARKit.MouthDimpleRight, s * 0.18f)
                maxW(ARKit.MouthShrugLower,  s * 0.14f)
                // Подавляем JawOpen при зубных щелевых
                weights[ARKit.JawOpen] *= 0.12f
            }

            // ── Ш / Ж: средний ZCR + средние частоты ─────────────────────
            mid > FRICATIVE_MID_ENERGY && features.zcr > FRICATIVE_ZCR_MID -> {
                maxW(ARKit.MouthPucker,      s * 0.40f)
                maxW(ARKit.MouthFunnel,      s * 0.30f)
                maxW(ARKit.MouthShrugUpper,  s * 0.24f)
                maxW(ARKit.MouthShrugLower,  s * 0.14f)
                maxW(ARKit.MouthRollLower,   s * 0.12f)
                weights[ARKit.JawOpen] *= 0.15f
            }

            // ── Ф / В: нижняя губа к зубам ───────────────────────────────
            rms < 0.28f -> {
                val fv = (s * 1.4f).coerceAtMost(0.82f)
                maxW(ARKit.MouthRollLower,   fv * 0.78f)
                maxW(ARKit.MouthUpperUpLeft, fv * 0.26f)
                maxW(ARKit.MouthUpperUpRight,fv * 0.26f)
                maxW(ARKit.MouthClose,       fv * 0.32f)
                maxW(ARKit.MouthShrugUpper,  fv * 0.16f)
                weights[ARKit.JawOpen] *= 0.35f
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ФАЗА 4: DENTALS
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyDentals(
        features: AudioFeatures,
        mid: Float, midRatio: Float, hi: Float, rms: Float,
    ) {
        // Дентальные: средние частоты доминируют, тихий сигнал, мало ВЧ, низкий ZCR
        if (midRatio <= 0.40f || rms >= 0.24f || hi >= 0.09f || features.zcr >= 0.20f) return

        val s = (mid * 1.35f).coerceAtMost(0.62f)
        maxW(ARKit.JawOpen,             s * 0.20f)
        maxW(ARKit.MouthShrugLower,     s * 0.44f)
        maxW(ARKit.MouthLowerDownLeft,  s * 0.18f * asymL)
        maxW(ARKit.MouthLowerDownRight, s * 0.18f * asymR)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ФАЗА 5: ГЛАСНЫЕ — применяем аккумуляторы к blendshapes
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyAA(s: Float, lo: Float) {
        if (s < 0.025f) return
        val v = s.coerceAtMost(0.94f)
        // Широкое открытие рта
        maxW(ARKit.MouthLowerDownLeft,  v * 0.18f * asymL)
        maxW(ARKit.MouthLowerDownRight, v * 0.18f * asymR)
        maxW(ARKit.MouthUpperUpLeft,    v * 0.07f)
        maxW(ARKit.MouthUpperUpRight,   v * 0.07f)
        maxW(ARKit.MouthStretchLeft,    v * 0.06f * asymL)
        maxW(ARKit.MouthStretchRight,   v * 0.06f * asymR)
        // Лёгкий funnel от низких частот
        maxW(ARKit.MouthFunnel,         lo * 0.06f)
    }

    private fun applyEE(s: Float) {
        if (s < 0.025f) return
        val v = s.coerceAtMost(0.88f)
        // Растяжение уголков
        maxW(ARKit.MouthStretchLeft,  v * 0.30f * asymL)
        maxW(ARKit.MouthStretchRight, v * 0.30f * asymR)
        maxW(ARKit.MouthSmileLeft,    v * 0.08f * asymL)
        maxW(ARKit.MouthSmileRight,   v * 0.08f * asymR)
        maxW(ARKit.MouthDimpleLeft,   v * 0.06f)
        maxW(ARKit.MouthDimpleRight,  v * 0.06f)
        maxW(ARKit.MouthShrugLower,   v * 0.10f)
        // Е/И сужает челюсть относительно гласной А
        weights[ARKit.JawOpen] *= 0.30f
    }

    private fun applyOO(s: Float) {
        if (s < 0.025f) return
        val v = s.coerceAtMost(0.90f)
        // Округление губ
        maxW(ARKit.MouthFunnel,      v * 0.40f)
        maxW(ARKit.MouthPucker,      v * 0.30f)
        maxW(ARKit.MouthPressLeft,   v * 0.10f)
        maxW(ARKit.MouthPressRight,  v * 0.10f)
        maxW(ARKit.MouthRollLower,   v * 0.10f)
        maxW(ARKit.MouthRollUpper,   v * 0.07f)
        // О/У — средне открытая челюсть (уже задана Jaw Anchor)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ФАЗА 6: ЭМОЦИИ
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyEmotions(
        emotion: EmotionalProsody,
        pitchVariance: Float,
        rms: Float,
    ) {
        // Pitch variance boost: экспрессивная речь усиливает эмоции
        val exprBoost = 1f + pitchVariance * 0.42f

        // ── ПОЗИТИВНАЯ ВАЛЕНЦИЯ → улыбка (Duchenne) ──────────────────────
        if (emotion.valence > VALENCE_POS_THR) {
            val s = (emotion.valence * exprBoost).coerceAtMost(1f)
            val speechBoost = 1f + rms * 0.22f

            maxW(ARKit.MouthSmileLeft,    s * 0.64f * speechBoost * asymL)
            maxW(ARKit.MouthSmileRight,   s * 0.60f * speechBoost * asymR)
            maxW(ARKit.CheekSquintLeft,   s * 0.50f * asymL)
            maxW(ARKit.CheekSquintRight,  s * 0.50f * asymR)

            if (s > 0.32f) {
                val eyeSmile = (s - 0.32f) * 0.55f
                maxW(ARKit.EyeSquintLeft,  eyeSmile * asymL)
                maxW(ARKit.EyeSquintRight, eyeSmile * asymR)
            }
            if (s > 0.52f) {
                val puff = (s - 0.52f) * 0.20f
                maxW(ARKit.CheekPuff,      puff)
                maxW(ARKit.NoseSneerLeft,  (s - 0.52f) * 0.10f)
                maxW(ARKit.NoseSneerRight, (s - 0.52f) * 0.10f)
            }
        }

        // ── НЕГАТИВНАЯ ВАЛЕНЦИЯ → хмурость ───────────────────────────────
        if (emotion.valence < VALENCE_NEG_THR) {
            val f = (-emotion.valence * exprBoost).coerceAtMost(1f)

            maxW(ARKit.MouthFrownLeft,  f * 0.56f * asymL)
            maxW(ARKit.MouthFrownRight, f * 0.56f * asymR)
            maxW(ARKit.BrowDownLeft,    f * 0.66f * asymL)
            maxW(ARKit.BrowDownRight,   f * 0.62f * asymR)
            maxW(ARKit.BrowInnerUp,     f * 0.36f)
            maxW(ARKit.NoseSneerLeft,   f * 0.24f * asymL)
            maxW(ARKit.NoseSneerRight,  f * 0.24f * asymR)
            maxW(ARKit.EyeSquintLeft,   f * 0.18f)
            maxW(ARKit.EyeSquintRight,  f * 0.18f)
            maxW(ARKit.MouthLowerDownLeft,  f * 0.10f)
            maxW(ARKit.MouthLowerDownRight, f * 0.10f)
        }

        // ── AROUSAL → широко открытые глаза ──────────────────────────────
        if (emotion.arousal > AROUSAL_THR) {
            val a = emotion.arousal - AROUSAL_THR
            maxW(ARKit.BrowInnerUp,      a * 0.52f)
            maxW(ARKit.BrowOuterUpLeft,  a * 0.40f * asymL)
            maxW(ARKit.BrowOuterUpRight, a * 0.38f * asymR)

            if (emotion.arousal > 0.52f) {
                val wide = (emotion.arousal - 0.52f) * 0.42f
                maxW(ARKit.EyeWideLeft,  wide * asymL)
                maxW(ARKit.EyeWideRight, wide * asymR)
            }
        }

        // ── THOUGHTFULNESS → сосредоточенность ───────────────────────────
        if (emotion.thoughtfulness > THOUGHT_THR) {
            val t = emotion.thoughtfulness
            maxW(ARKit.BrowInnerUp,      t * 0.38f)
            maxW(ARKit.BrowDownLeft,     t * 0.15f * asymL)
            maxW(ARKit.BrowDownRight,    t * 0.15f * asymR)
            maxW(ARKit.MouthPressLeft,   t * 0.32f * asymL)
            maxW(ARKit.MouthPressRight,  t * 0.32f * asymR)
            maxW(ARKit.MouthRollLower,   t * 0.18f)
            maxW(ARKit.MouthRollUpper,   t * 0.09f)
            maxW(ARKit.EyeSquintLeft,    t * 0.14f)
            maxW(ARKit.EyeSquintRight,   t * 0.14f)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    /** Hermite smoothstep: f(t) = t²(3 − 2t). Применяется к взрывным. */
    private fun easeSoft(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Устанавливает weights[idx] = max(weights[idx], value). */
    private fun maxW(idx: Int, value: Float): Float {
        val v = maxOf(weights[idx], value)
        weights[idx] = v
        return v
    }

    /**
     * Neuro-Latent Asymmetry.
     * Два несинхронных синуса — левое и правое полушарие.
     * Обновляется один раз за кадр (~60fps).
     */
    private fun updateAsymmetry() {
        neuroPhase += ASYM_DT * ASYM_FREQ * (2f * Math.PI.toFloat())
        asymL = ASYM_BASE + sin(neuroPhase)              * ASYM_AMP
        asymR = ASYM_BASE + sin(neuroPhase * ASYM_PHASE_R) * ASYM_AMP
    }

    private fun clampAll() {
        for (i in 0 until ARKit.COUNT)
            weights[i] = weights[i].coerceIn(0f, 1f)
    }
}