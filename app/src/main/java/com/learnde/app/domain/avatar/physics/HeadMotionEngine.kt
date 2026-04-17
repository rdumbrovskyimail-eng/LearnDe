// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/domain/avatar/physics/HeadMotionEngine.kt
//
// ФИКС:
//   • После интеграции spring pitch/yaw/roll проходят через
//     sanitize: если non-finite → 0. Защита от NaN-распространения,
//     которое могло повернуть голову "в бесконечность".
// ═══════════════════════════════════════════════════════════
package com.learnde.app.domain.avatar.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class HeadMotionEngine {

    var pitch: Float = 0f; private set
    var yaw:   Float = 0f; private set
    var roll:  Float = 0f; private set

    private var pitchVel = 0f
    private var yawVel   = 0f
    private var rollVel  = 0f

    private var focalYaw   = 0f
    private var focalPitch = 0f

    private var saccadeTimer     = randomSaccadeInterval(isSpeaking = false)
    private var saccadeCooldown  = 0f

    private var cogYawTarget   = 0f
    private var cogPitchTarget = 0f
    private var cogActive      = false

    private var nodOffset   = 0f
    private var nodCooldown = 0f

    private var emphasisYaw      = 0f
    private var emphasisCooldown = 0f
    private var emphasisDir      = 1f

    private var breathPhase  = Random.nextFloat() * TAU
    private var swayPhase1   = Random.nextFloat() * TAU
    private var swayPhase2   = Random.nextFloat() * TAU
    private var swayPhase3   = Random.nextFloat() * TAU

    companion object {
        private const val TAU = (2.0 * Math.PI).toFloat()

        private const val NECK_K = 32f
        private val       NECK_D = 2f * sqrt(NECK_K)

        private const val MAX_PITCH      = 13f
        private const val MAX_YAW        = 16f
        private const val MAX_ROLL       = 6f

        private const val IDLE_YAW_RANGE   = 3.0f
        private const val IDLE_PITCH_RANGE = 2.0f
        private const val SPEAK_YAW_RANGE  = 1.5f
        private const val SPEAK_PITCH_RANGE = 1.0f

        private const val COG_YAW_MAG   = 4.5f
        private const val COG_PITCH_MAG = 3.0f
        private const val COG_THRESHOLD = 0.20f

        private const val NOD_IMPULSE       = -4.0f
        private const val NOD_RETURN_SPEED  = 16f
        private const val NOD_FLUX_THRESHOLD = 0.28f
        private const val NOD_COOLDOWN_MIN   = 0.18f
        private const val NOD_COOLDOWN_MAX   = 0.30f

        private const val EMPHASIS_MAG         = 4.5f
        private const val EMPHASIS_AROUSAL_THR = 0.42f
        private const val EMPHASIS_COOLDOWN_MIN = 0.9f
        private const val EMPHASIS_COOLDOWN_MAX = 1.6f

        private const val SWAY_PITCH_AMP = 0.35f
        private const val SWAY_YAW_AMP   = 0.40f
        private const val SWAY_ROLL_AMP  = 0.18f
        private const val BREATH_AMP     = 0.55f

        private const val SACCADE_SPEAK_MIN  = 0.9f
        private const val SACCADE_SPEAK_MAX  = 2.2f
        private const val SACCADE_IDLE_MIN   = 1.4f
        private const val SACCADE_IDLE_MAX   = 3.5f
        private const val SACCADE_COG_MIN    = 0.8f
        private const val SACCADE_COG_MAX    = 2.0f
        private const val SACCADE_COOLDOWN   = 0.12f

        private const val GAZE_CONTACT_PROB_SPEAKING = 0.92f
        private const val GAZE_CONTACT_PROB_IDLE     = 0.82f
    }

    fun update(
        dtMs:          Long,
        rms:           Float,
        arousal:       Float,
        thoughtfulness: Float,
        isSpeaking:    Boolean,
        flux:          Float,
    ) {
        // Sanitize входных
        val safeArousal       = if (arousal.isFinite()) arousal.coerceIn(0f, 1f) else 0f
        val safeThoughtfulness = if (thoughtfulness.isFinite()) thoughtfulness.coerceIn(0f, 1f) else 0f
        val safeRms           = if (rms.isFinite()) rms.coerceIn(0f, 1f) else 0f
        val safeFlux          = if (flux.isFinite()) flux.coerceIn(0f, 1f) else 0f

        val dt = dtMs.coerceIn(1, 32) / 1000f

        val breathSpeed = 1f + safeArousal * 0.35f
        breathPhase += dt * 0.78f * breathSpeed
        swayPhase1  += dt * 0.31f
        swayPhase2  += dt * 0.47f
        swayPhase3  += dt * 0.37f

        val breathPitch = sin(breathPhase) * BREATH_AMP
        val swayPitch = (sin(swayPhase1) + sin(swayPhase3 * 1.27f) * 0.28f) * SWAY_PITCH_AMP
        val swayYaw   = (sin(swayPhase2) + cos(swayPhase3 * 0.71f) * 0.32f) * SWAY_YAW_AMP
        val swayRoll  = sin(swayPhase3 + swayPhase1 * 0.19f) * SWAY_ROLL_AMP

        val swayScale = if (isSpeaking) 0.15f else 1.0f

        saccadeTimer    -= dt
        saccadeCooldown  = (saccadeCooldown - dt).coerceAtLeast(0f)

        if (saccadeTimer <= 0f && saccadeCooldown <= 0f) {
            updateFocalTarget(safeThoughtfulness, isSpeaking)
            saccadeTimer    = randomSaccadeInterval(isSpeaking, safeThoughtfulness)
            saccadeCooldown = SACCADE_COOLDOWN
        }

        updateCognitiveLook(safeThoughtfulness, dt)

        nodCooldown = (nodCooldown - dt).coerceAtLeast(0f)
        if (isSpeaking && safeFlux > NOD_FLUX_THRESHOLD && nodCooldown <= 0f) {
            val strength = (0.55f + safeFlux * 3.5f).coerceAtMost(1.4f)
            nodOffset = NOD_IMPULSE * strength * (1f + safeArousal * 0.25f)
            nodCooldown = NOD_COOLDOWN_MIN +
                    Random.nextFloat() * (NOD_COOLDOWN_MAX - NOD_COOLDOWN_MIN)
        }
        nodOffset += (0f - nodOffset) * NOD_RETURN_SPEED * dt

        emphasisCooldown = (emphasisCooldown - dt).coerceAtLeast(0f)
        if (isSpeaking &&
            safeArousal > EMPHASIS_AROUSAL_THR &&
            emphasisCooldown <= 0f) {
            emphasisDir = -emphasisDir
            emphasisYaw = EMPHASIS_MAG * emphasisDir * safeArousal *
                    (0.65f + Random.nextFloat() * 0.35f)
            emphasisCooldown = EMPHASIS_COOLDOWN_MIN +
                    Random.nextFloat() * (EMPHASIS_COOLDOWN_MAX - EMPHASIS_COOLDOWN_MIN)
        }
        emphasisYaw *= (1f - dt * 4.5f).coerceAtLeast(0f)

        val targetPitch = (focalPitch + cogPitchTarget + nodOffset +
                (breathPitch + swayPitch) * swayScale)
            .coerceIn(-MAX_PITCH, MAX_PITCH)

        val targetYaw = (focalYaw + cogYawTarget + emphasisYaw +
                swayYaw * swayScale)
            .coerceIn(-MAX_YAW, MAX_YAW)

        val targetRoll = (-targetYaw * 0.14f + swayRoll * swayScale)
            .coerceIn(-MAX_ROLL, MAX_ROLL)

        pitchVel += ((targetPitch - pitch) * NECK_K - pitchVel * NECK_D) * dt
        yawVel   += ((targetYaw   - yaw)   * NECK_K - yawVel   * NECK_D) * dt
        rollVel  += ((targetRoll  - roll)  * NECK_K - rollVel  * NECK_D) * dt

        pitch = (pitch + pitchVel * dt).coerceIn(-MAX_PITCH, MAX_PITCH)
        yaw   = (yaw   + yawVel   * dt).coerceIn(-MAX_YAW,   MAX_YAW)
        roll  = (roll  + rollVel  * dt).coerceIn(-MAX_ROLL,  MAX_ROLL)

        // Final sanitize: если что-то ушло в non-finite — сброс
        if (!pitch.isFinite()) { pitch = 0f; pitchVel = 0f }
        if (!yaw.isFinite())   { yaw   = 0f; yawVel   = 0f }
        if (!roll.isFinite())  { roll  = 0f; rollVel  = 0f }
    }

    fun reset() {
        pitch = 0f; yaw = 0f; roll = 0f
        pitchVel = 0f; yawVel = 0f; rollVel = 0f
        focalYaw = 0f; focalPitch = 0f
        saccadeTimer = randomSaccadeInterval(false)
        saccadeCooldown = 0f
        cogYawTarget = 0f; cogPitchTarget = 0f; cogActive = false
        nodOffset = 0f; nodCooldown = 0f
        emphasisYaw = 0f; emphasisCooldown = 0f; emphasisDir = 1f
    }

    private fun updateFocalTarget(thoughtfulness: Float, isSpeaking: Boolean) {
        if (thoughtfulness > COG_THRESHOLD) return

        val contactProb = if (isSpeaking)
            GAZE_CONTACT_PROB_SPEAKING else GAZE_CONTACT_PROB_IDLE

        if (Random.nextFloat() < contactProb) {
            focalYaw   = (Random.nextFloat() - 0.5f) * 1.8f
            focalPitch = (Random.nextFloat() - 0.5f) * 1.2f
        } else {
            val yawRange   = if (isSpeaking) SPEAK_YAW_RANGE   else IDLE_YAW_RANGE
            val pitchRange = if (isSpeaking) SPEAK_PITCH_RANGE  else IDLE_PITCH_RANGE
            focalYaw   = (Random.nextFloat() - 0.5f) * 2f * yawRange
            focalPitch = (Random.nextFloat() - 0.5f) * 2f * pitchRange
        }
    }

    private fun updateCognitiveLook(thoughtfulness: Float, dt: Float) {
        if (thoughtfulness > COG_THRESHOLD) {
            if (!cogActive) {
                cogYawTarget   = if (Random.nextBoolean()) COG_YAW_MAG else -COG_YAW_MAG
                cogPitchTarget = COG_PITCH_MAG
                cogActive = true
            }
            cogYawTarget   = cogYawTarget.let {
                val sign = if (it > 0f) 1f else -1f
                sign * COG_YAW_MAG * thoughtfulness
            }
            cogPitchTarget = COG_PITCH_MAG * thoughtfulness
        } else {
            if (cogActive) {
                cogYawTarget   *= (1f - dt * 5f).coerceAtLeast(0f)
                cogPitchTarget *= (1f - dt * 5f).coerceAtLeast(0f)
                if (abs(cogYawTarget) < 0.1f && abs(cogPitchTarget) < 0.1f) {
                    cogYawTarget = 0f; cogPitchTarget = 0f
                    cogActive = false
                }
            }
        }
    }

    private fun randomSaccadeInterval(
        isSpeaking: Boolean,
        thoughtfulness: Float = 0f,
    ): Float {
        return when {
            thoughtfulness > COG_THRESHOLD ->
                SACCADE_COG_MIN + Random.nextFloat() * (SACCADE_COG_MAX - SACCADE_COG_MIN)
            isSpeaking ->
                SACCADE_SPEAK_MIN + Random.nextFloat() * (SACCADE_SPEAK_MAX - SACCADE_SPEAK_MIN)
            else ->
                SACCADE_IDLE_MIN + Random.nextFloat() * (SACCADE_IDLE_MAX - SACCADE_IDLE_MIN)
        }
    }
}
