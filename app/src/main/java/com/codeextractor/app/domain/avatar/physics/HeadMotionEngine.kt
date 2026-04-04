package com.codeextractor.app.domain.avatar.physics

import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

/**
 * Генерирует реалистичные движения головы:
 * - Idle sway: лёгкое покачивание в покое (фигуры Лиссажу)
 * - Speech nod: кивки на ударных слогах (RMS-спайки)
 * - Emphasis yaw: повороты при эмоциональных акцентах
 * - Settle: плавное затухание после речи
 */
class HeadMotionEngine {

    // Output values в радианах (конвертируем в градусы при отдаче)
    var pitch: Float = 0f; private set   // вверх-вниз
    var yaw: Float = 0f; private set     // лево-право
    var roll: Float = 0f; private set    // наклон

    // Smoothed velocities
    private var pitchVel = 0f
    private var yawVel = 0f
    private var rollVel = 0f

    // Idle oscillation phases (разные частоты → органичный паттерн)
    private var idlePhase1 = Random.nextFloat() * 6.28f
    private var idlePhase2 = Random.nextFloat() * 6.28f
    private var idlePhase3 = Random.nextFloat() * 6.28f

    // Speech tracking
    private var prevRms = 0f
    private var rmsAccum = 0f
    private var nodCooldown = 0f

    // Emphasis tracking
    private var emphasisCooldown = 0f
    private var lastYawDir = 1f

    // Constants
    companion object {
        // Idle sway amplitudes (градусы)
        private const val IDLE_PITCH_AMP = 1.5f
        private const val IDLE_YAW_AMP = 2.0f
        private const val IDLE_ROLL_AMP = 0.8f

        // Speech nod
        private const val NOD_IMPULSE = 3.5f       // градусы за кивок
        private const val NOD_COOLDOWN = 0.35f      // сек между кивками
        private const val RMS_SPIKE_THRESHOLD = 0.08f

        // Emphasis turn
        private const val EMPHASIS_YAW = 4.0f       // градусы
        private const val EMPHASIS_COOLDOWN = 1.2f

        // Spring parameters
        private const val STIFFNESS = 25f
        private const val DAMPING = 8f

        // Max range (градусы) — чтобы голова не улетала
        private const val MAX_PITCH = 8f
        private const val MAX_YAW = 10f
        private const val MAX_ROLL = 5f
    }

    /**
     * @param dtMs      delta time
     * @param rms       текущий RMS (0..1)
     * @param arousal   эмоциональная интенсивность (0..1)
     * @param isSpeaking AI сейчас говорит
     */
    fun update(dtMs: Long, rms: Float, arousal: Float, isSpeaking: Boolean) {
        val dt = (dtMs.coerceIn(1, 32)) / 1000f

        // ─── IDLE SWAY (всегда, ослабевает во время речи) ───
        val idleScale = if (isSpeaking) 0.3f else 1.0f

        idlePhase1 += dt * 0.37f  // очень медленный
        idlePhase2 += dt * 0.53f  // чуть быстрее, некратная частота
        idlePhase3 += dt * 0.41f

        val idlePitch = sin(idlePhase1) * IDLE_PITCH_AMP * idleScale
        val idleYaw = sin(idlePhase2) * IDLE_YAW_AMP * idleScale +
                cos(idlePhase3 * 0.7f) * IDLE_YAW_AMP * 0.3f * idleScale
        val idleRoll = sin(idlePhase3) * IDLE_ROLL_AMP * idleScale

        // ─── SPEECH NOD (кивок на ударных слогах) ───
        var nodTarget = 0f
        nodCooldown = (nodCooldown - dt).coerceAtLeast(0f)

        if (isSpeaking) {
            val spike = rms - prevRms
            rmsAccum = rmsAccum * 0.85f + spike * 0.15f

            if (spike > RMS_SPIKE_THRESHOLD && nodCooldown <= 0f) {
                nodTarget = -NOD_IMPULSE * (0.7f + spike * 3f).coerceAtMost(1.5f)
                nodCooldown = NOD_COOLDOWN
            }
        }
        prevRms = rms

        // ─── EMPHASIS YAW (поворот при эмоциональных пиках) ───
        var emphTarget = 0f
        emphasisCooldown = (emphasisCooldown - dt).coerceAtLeast(0f)

        if (isSpeaking && arousal > 0.5f && emphasisCooldown <= 0f) {
            lastYawDir = -lastYawDir // чередуем лево-право
            emphTarget = EMPHASIS_YAW * lastYawDir * arousal
            emphasisCooldown = EMPHASIS_COOLDOWN
        }

        // ─── SPRING INTEGRATION ───
        val targetPitch = idlePitch + nodTarget
        val targetYaw = idleYaw + emphTarget
        val targetRoll = idleRoll

        // Пружинная модель (те же формулы что FacePhysicsEngine)
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
        prevRms = 0f; rmsAccum = 0f
    }
}
