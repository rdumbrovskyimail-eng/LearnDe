package com.codeextractor.app.domain.avatar

/**
 * LinguisticState — Состояние лингвистического канала на текущем кадре.
 *
 * Объединяет:
 *   Gate (VisemeGroup)    — разрешённый диапазон артикуляции
 *   Target (6 float)      — точная целевая поза из PhonemeProfile
 *   Next Target           — поза следующей фонемы для ко-артикуляции
 *   Progress              — [0..1] прогресс внутри текущей фонемы
 *   Predictive Emotion    — пунктуация из look-ahead
 *   Confidence            — доверие к текстовому каналу [0..1]
 *
 * ZERO-ALLOCATION: все поля — примитивы, обновляются in-place.
 */
class LinguisticState {

    // ── GATE ──────────────────────────────────────────────────────────────
    var currentGate: VisemeGroup = VisemeGroup.SILENCE; private set
    var nextGate: VisemeGroup = VisemeGroup.SILENCE; private set

    // ── TARGET ────────────────────────────────────────────────────────────
    var jawOpen: Float = 0f; private set
    var lipRound: Float = 0f; private set
    var lipSpread: Float = 0f; private set
    var lipClose: Float = 0f; private set
    var tongueUp: Float = 0f; private set
    var teethClose: Float = 0f; private set

    // ── NEXT TARGET ───────────────────────────────────────────────────────
    var nextJawOpen: Float = 0f; private set
    var nextLipRound: Float = 0f; private set
    var nextLipSpread: Float = 0f; private set
    var nextLipClose: Float = 0f; private set

    // ── PROGRESS ──────────────────────────────────────────────────────────
    var progress: Float = 0f; private set
    var isTransition: Boolean = false; private set

    // ── PREDICTIVE EMOTION ────────────────────────────────────────────────
    var predictedEmotion: PunctuationType = PunctuationType.NEUTRAL; private set

    // ── CONFIDENCE ────────────────────────────────────────────────────────
    var textConfidence: Float = 0f; private set

    enum class PunctuationType { NEUTRAL, QUESTION, EXCLAMATION, PAUSE }

    fun update(
        gate: VisemeGroup,
        nextG: VisemeGroup,
        profile: PhonemeData.PhonemeProfile,
        nextProfile: PhonemeData.PhonemeProfile?,
        prog: Float,
        transition: Boolean,
        punct: PunctuationType,
        confidence: Float,
    ) {
        currentGate = gate; nextGate = nextG
        jawOpen = profile.jawOpen; lipRound = profile.lipRound
        lipSpread = profile.lipSpread; lipClose = profile.lipClose
        tongueUp = profile.tongueUp; teethClose = profile.teethClose
        nextProfile?.let {
            nextJawOpen = it.jawOpen; nextLipRound = it.lipRound
            nextLipSpread = it.lipSpread; nextLipClose = it.lipClose
        } ?: run {
            nextJawOpen = jawOpen; nextLipRound = lipRound
            nextLipSpread = lipSpread; nextLipClose = lipClose
        }
        progress = prog; isTransition = transition
        predictedEmotion = punct; textConfidence = confidence
    }

    fun reset() {
        currentGate = VisemeGroup.SILENCE; nextGate = VisemeGroup.SILENCE
        jawOpen = 0f; lipRound = 0f; lipSpread = 0f
        lipClose = 0f; tongueUp = 0f; teethClose = 0f
        nextJawOpen = 0f; nextLipRound = 0f; nextLipSpread = 0f; nextLipClose = 0f
        progress = 0f; isTransition = false
        predictedEmotion = PunctuationType.NEUTRAL; textConfidence = 0f
    }
}