package com.codeextractor.app.domain.avatar.physics

import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

/**
 * HeadMotionEngine v3 — объединение лучших подходов.
 *
 * - Irrational idle frequencies (Claude): несоизмеримые частоты → никогда не повторяется.
 * - Breathing phase (Gemini): дыхание влияет на шею.
 * - Cognitive gaze shift (Gemini): при thoughtfulness > 0.2 голова отводится
 *   вверх-в-сторону (реальное поведение при размышлении).
 * - Flux-driven nods (Gemini): кивки привязаны к spectralFlux (ударные слоги),
 *   а не к RMS (громкость). Это точнее совпадает с ритмом речи.
 * - Emphasis yaw (Claude): повороты при arousal.
 */
class HeadMotionEngine {

    var pitch: Float = 0f; private set
    var yaw: Float = 0f; private set
    var roll: Float = 0f; private set

    private var pitchVel = 0f
    private var yawVel = 0f
    private var rollVel = 0f

    // Idle: несоизмеримые частоты (Claude)
    private var idlePhase1 = Random.nextFloat() * 6.28f
    private var idlePhase2 = Random.nextFloat() * 6.28f
    private var idlePhase3 = Random.nextFloat() * 6.28f
    private var breathPhase = Random.nextFloat() * 6.28f

    // Nod (Gemini: flux-driven)
    private var nodOffset = 0f
    private var nodCooldown = 0f

    // Emphasis yaw (Claude)
    private var emphasisCooldown = 0f
    private var lastYawDir = 1f

    // Cognitive gaze shift (Gemini)
    private var thoughtYawTarget = 0f
    private var thoughtPitchTarget = 0f

    companion object {
        private const val IDLE_PITCH_AMP = 1.6f
        private const val IDLE_YAW_AMP = 2.2f
        private const val IDLE_ROLL_AMP = 0.8f

        private const val NOD_IMPULSE = 3.2f
        private const val NOD_COOLDOWN_BASE = 0.35f

        private const val EMPHASIS_YAW = 4.0f
        private const val EMPHASIS_COOLDOWN_BASE = 1.0f

        private const val STIFFNESS = 28f
        private const val DAMPING = 9.5f

        private const val MAX_PITCH = 12f
        private const val MAX_YAW = 15f
        private const val MAX_ROLL = 6f
    }

    /**
     * @param dtMs            delta time
     * @param rms             текущий RMS (0..1)
     * @param arousal         возбуждение (0..1)
     * @param thoughtfulness  задумчивость (0..1) — из ProsodyTracker
     * @param isSpeaking      AI говорит
     * @param flux            spectralFlux (0..1) — для ударных кивков
     */
    fun update(
        dtMs: Long,
        rms: Float,
        arousal: Float,
        thoughtfulness: Float,
        isSpeaking: Boolean,
        flux: Float
    ) {
        val dt = (dtMs.coerceIn(1, 32)) / 1000f

        // ══════════════════════════════════════════
        //  1. IDLE + BREATHING (Claude + Gemini)
        // ══════════════════════════════════════════
        val idleScale = if (isSpeaking) 0.2f else 1.0f

        // Дыхание ускоряется при высоком arousal (Gemini)
        val breathSpeed = 1f + arousal * 0.5f
        breathPhase += dt * 0.7f * breathSpeed

        // Несоизмеримые частоты (Claude) — апериодичность
        idlePhase1 += dt * 0.31f
        idlePhase2 += dt * 0.47f
        idlePhase3 += dt * 0.37f

        // Breathing: грудь расширяется → голова слегка назад
        val breathPitch = sin(breathPhase) * 0.8f * idleScale

        val idlePitch = (sin(idlePhase1) + sin(idlePhase3 * 1.3f) * 0.3f) * IDLE_PITCH_AMP * idleScale + breathPitch
        val idleYaw = (sin(idlePhase2) + cos(idlePhase3 * 0.7f) * 0.35f) * IDLE_YAW_AMP * idleScale
        val idleRoll = sin(idlePhase3 + idlePhase1 * 0.2f) * IDLE_ROLL_AMP * idleScale

        // ══════════════════════════════════════════
        //  2. COGNITIVE GAZE SHIFT (Gemini)
        //  При задумчивости: голова уходит вверх-в-сторону
        // ══════════════════════════════════════════
        var cogPitch = 0f
        var cogYaw = 0f

        if (thoughtfulness > 0.2f) {
            // Выбираем сторону один раз (пока thoughtfulness > 0.2)
            if (thoughtYawTarget == 0f) {
                thoughtYawTarget = if (Random.nextBoolean()) 6f else -6f
                thoughtPitchTarget = 4f  // вверх (вспоминание)
            }
            cogPitch = thoughtPitchTarget * thoughtfulness
            cogYaw = thoughtYawTarget * thoughtfulness
        } else {
            // Сбрасываем цель — пружины вернут голову
            thoughtYawTarget = 0f
            thoughtPitchTarget = 0f
        }

        // ══════════════════════════════════════════
        //  3. FLUX-DRIVEN NODS (Gemini)
        //  Кивки привязаны к spectralFlux (ударные слоги),
        //  а не к RMS — точнее совпадают с ритмом речи.
        // ══════════════════════════════════════════
        nodCooldown = (nodCooldown - dt).coerceAtLeast(0f)

        if (isSpeaking && flux > 0.3f && nodCooldown <= 0f) {
            val nodStrength = (0.6f + flux * 4f).coerceAtMost(1.5f)
            nodOffset = -NOD_IMPULSE * nodStrength * (1f + arousal * 0.3f)
            nodCooldown = NOD_COOLDOWN_BASE + Random.nextFloat() * 0.15f
        }
        // Упругое возвращение (Gemini)
        nodOffset += (0f - nodOffset) * 14f * dt

        // ══════════════════════════════════════════
        //  4. EMPHASIS YAW (Claude)
        // ══════════════════════════════════════════
        emphasisCooldown = (emphasisCooldown - dt).coerceAtLeast(0f)
        var emphYaw = 0f

        if (isSpeaking && arousal > 0.45f && emphasisCooldown <= 0f) {
            lastYawDir = -lastYawDir
            emphYaw = EMPHASIS_YAW * lastYawDir * arousal * (0.7f + Random.nextFloat() * 0.3f)
            emphasisCooldown = EMPHASIS_COOLDOWN_BASE * (0.8f + Random.nextFloat() * 0.4f)
        }

        // ══════════════════════════════════════════
        //  5. SPRING INTEGRATION
        // ══════════════════════════════════════════
        val targetPitch = idlePitch + nodOffset + cogPitch
        val targetYaw = idleYaw + emphYaw + cogYaw
        val targetRoll = idleRoll

        pitchVel += ((targetPitch - pitch) * STIFFNESS - pitchVel * DAMPING) * dt
        yawVel += ((targetYaw - yaw) * STIFFNESS - yawVel * DAMPING) * dt
        rollVel += ((targetRoll - roll) * STIFFNESS - rollVel * DAMPING) * dt

        pitch = (pitch + pitchVel * dt).coerceIn(-MAX_PITCH, MAX_PITCH)
        yaw = (yaw + yawVel * dt).coerceIn(-MAX_YAW, MAX_YAW)
        roll = (roll + rollVel * dt).coerceIn(-MAX_ROLL, MAX_ROLL)
    }

    fun reset() {
        pitch = 0f; yaw = 0f; roll = 0f
        pitchVel = 0f; yawVel = 0f; rollVel = 0f
        nodOffset = 0f; nodCooldown = 0f; emphasisCooldown = 0f
        thoughtYawTarget = 0f; thoughtPitchTarget = 0f
    }
}
