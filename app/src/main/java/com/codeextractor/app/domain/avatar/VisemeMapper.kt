package com.codeextractor.app.domain.avatar

class VisemeMapper {

    private val weights = FloatArray(ARKit.COUNT)

    /**
     * Convert DSP features + emotion → target morph weights.
     * Returns pre-allocated array (do NOT store reference).
     */
    fun map(features: AudioFeatures, emotion: EmotionalProsody): FloatArray {
        weights.fill(0f)

        if (!features.hasVoice) return weights

        val rms = features.rms
        val lo = features.energyLow
        val mid = features.energyMid
        val hi = features.energyHigh

        // ─── JAW: opens with amplitude, biased by low freqs ───
        weights[ARKit.JawOpen] = (rms * 0.6f + lo * 0.5f).coerceIn(0f, 0.85f)

        // ─── VISEME SHAPES from spectral dominance ───

        // LOW dominant (A, O): wide open mouth
        if (lo > mid && lo > hi) {
            weights[ARKit.JawOpen] = (weights[ARKit.JawOpen] + lo * 0.2f).coerceAtMost(0.9f)
            weights[ARKit.MouthLowerDownLeft] = lo * 0.3f
            weights[ARKit.MouthLowerDownRight] = lo * 0.3f
        }

        // MID dominant (E, I): stretched lips, less jaw
        if (mid > lo && mid > hi) {
            weights[ARKit.MouthStretchLeft] = mid * 0.55f
            weights[ARKit.MouthStretchRight] = mid * 0.55f
            weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.6f // reduce jaw for "ee"
        }

        // HIGH dominant (S, F, Sh): teeth together, tense
        if (hi > lo * 0.8f && hi > 0.15f) {
            weights[ARKit.MouthClose] = hi * 0.6f
            weights[ARKit.MouthStretchLeft] = (weights[ARKit.MouthStretchLeft] + hi * 0.25f)
            weights[ARKit.MouthStretchRight] = (weights[ARKit.MouthStretchRight] + hi * 0.25f)
            weights[ARKit.JawOpen] = weights[ARKit.JawOpen] * 0.3f
        }

        // O/U shape: funnel + pucker when low energy and moderate amplitude
        if (lo > mid * 1.3f && lo > 0.2f && hi < 0.15f) {
            weights[ARKit.MouthFunnel] = lo * 0.6f
            weights[ARKit.MouthPucker] = lo * 0.35f
        }

        // ─── EMOTION OVERLAY ───

        // Positive valence → smile
        if (emotion.valence > 0.15f) {
            val s = emotion.valence * 0.6f
            weights[ARKit.MouthSmileLeft] = s
            weights[ARKit.MouthSmileRight] = s * 0.95f // Slight asymmetry for realism
            weights[ARKit.CheekSquintLeft] = s * 0.4f
            weights[ARKit.CheekSquintRight] = s * 0.4f
        }

        // Negative valence → frown + brow down
        if (emotion.valence < -0.15f) {
            val f = -emotion.valence * 0.5f
            weights[ARKit.MouthFrownLeft] = f
            weights[ARKit.MouthFrownRight] = f
            weights[ARKit.BrowDownLeft] = f * 0.7f
            weights[ARKit.BrowDownRight] = f * 0.65f
            weights[ARKit.NoseSneerLeft] = f * 0.3f
            weights[ARKit.NoseSneerRight] = f * 0.3f
        }

        // Arousal → brow raise on emphasis
        if (emotion.arousal > 0.3f) {
            weights[ARKit.BrowInnerUp] = (emotion.arousal - 0.3f) * 0.4f
            weights[ARKit.BrowOuterUpLeft] = (emotion.arousal - 0.3f) * 0.2f
            weights[ARKit.BrowOuterUpRight] = (emotion.arousal - 0.3f) * 0.2f
        }

        // Thoughtfulness → slight brow knit + mouth press
        if (emotion.thoughtfulness > 0.3f) {
            val t = emotion.thoughtfulness * 0.3f
            weights[ARKit.BrowInnerUp] = (weights[ARKit.BrowInnerUp] + t).coerceAtMost(0.5f)
            weights[ARKit.MouthPressLeft] = t * 0.4f
            weights[ARKit.MouthPressRight] = t * 0.4f
        }

        // Clamp all
        for (i in 0 until ARKit.COUNT) weights[i] = weights[i].coerceIn(0f, 1f)

        return weights
    }
}
