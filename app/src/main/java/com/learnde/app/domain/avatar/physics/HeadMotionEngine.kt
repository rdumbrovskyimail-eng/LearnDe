package com.learnde.app.domain.avatar.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * HeadMotionEngine v4 — BioSaccade & VOR System
 *
 * БИОЛОГИЧЕСКАЯ МОДЕЛЬ ДВИЖЕНИЯ ГОЛОВЫ:
 *
 * Человеческая голова НЕ качается как маятник (синусоидный idle).
 * Реальный паттерн — «Фиксация → Саккада → Фиксация»:
 *
 *   1. FIXATION (удержание взора)
 *      Голова неподвижна. Мышцы шеи компенсируют микро-колебания тела.
 *      Единственное движение — постуральный sway (< 0.5°) от дыхания.
 *
 *   2. SACCADE (скачок взора)
 *      Глаза перебрасываются на новую цель за 30–50 мс (не моделируется здесь,
 *      это уровень IdleAnimator / EyeLook blendshapes).
 *      Если угол саккады > 12–15° — шея начинает поворот с задержкой 60–80 мс.
 *      Это Vestibulo-Ocular Reflex (VOR): глаза ведут, голова догоняет.
 *
 *   3. POSTURAL SWAY (постуральное покачивание)
 *      Тело отклоняется от оси гравитации → спинной мозг посылает
 *      короткий корректирующий импульс. Это НЕ синусоида, а
 *      случайное марковское блуждание с мягкими границами.
 *
 * АРХИТЕКТУРА:
 *
 *   FocalTarget     — текущая цель взора (yaw, pitch в градусах)
 *   SaccadeTimer    — таймер следующей смены цели (Марковская цепь состояний)
 *   CognitiveLook   — направление «взгляда в себя» при thoughtfulness > 0.2
 *   NodImpulse      — импульсный кивок, привязанный к spectralFlux (ударные слоги)
 *   EmphasisYaw     — поворот при высоком Arousal (акцентирование)
 *   BreathSway      — постуральное покачивание от дыхания (единственная синусоида)
 *
 *   Всё сходится в critically-damped spring (NECK_K, NECK_D):
 *   голова плавно но быстро следует за целью без overshooting.
 *
 * IRRATIONAL FREQUENCIES (постуральный sway):
 *   Три фазы с несоизмеримыми скоростями (0.31, 0.47, 0.37 Гц) —
 *   комбинация никогда не повторяется (апериодична).
 *   НО: амплитуда sway подавлена до < 0.4° — это фоновый шум, не маятник.
 *
 * Zero-allocation: все состояния — Float/Long примитивы, никаких объектов.
 */
class HeadMotionEngine {

    // ── Публичный выход (читается AvatarScene.onFrame) ───────────────────
    var pitch: Float = 0f; private set
    var yaw:   Float = 0f; private set
    var roll:  Float = 0f; private set

    // ── Spring velocities ─────────────────────────────────────────────────
    private var pitchVel = 0f
    private var yawVel   = 0f
    private var rollVel  = 0f

    // ── FocalTarget: текущая цель взора ──────────────────────────────────
    private var focalYaw   = 0f
    private var focalPitch = 0f

    // ── SaccadeTimer ─────────────────────────────────────────────────────
    private var saccadeTimer     = randomSaccadeInterval(isSpeaking = false)
    private var saccadeCooldown  = 0f   // минимальная пауза после саккады

    // ── CognitiveLook (взгляд «в себя» при thoughtfulness) ───────────────
    private var cogYawTarget   = 0f
    private var cogPitchTarget = 0f
    private var cogActive      = false

    // ── NodImpulse ────────────────────────────────────────────────────────
    private var nodOffset   = 0f
    private var nodCooldown = 0f

    // ── EmphasisYaw ───────────────────────────────────────────────────────
    private var emphasisYaw      = 0f
    private var emphasisCooldown = 0f
    private var emphasisDir      = 1f

    // ── Постуральный sway (дыхание + микро-колебания) ─────────────────────
    private var breathPhase  = Random.nextFloat() * TAU
    private var swayPhase1   = Random.nextFloat() * TAU
    private var swayPhase2   = Random.nextFloat() * TAU
    private var swayPhase3   = Random.nextFloat() * TAU

    companion object {
        private const val TAU = (2.0 * Math.PI).toFloat()

        // ── Neck spring (critically damped: d = 2*sqrt(k)) ───────────────
        private const val NECK_K = 32f
        private val       NECK_D = 2f * sqrt(NECK_K)   // ≈ 11.3

        // ── Limits (градусы) ──────────────────────────────────────────────
        private const val MAX_PITCH      = 13f
        private const val MAX_YAW        = 16f
        private const val MAX_ROLL       = 6f

        // ── FocalTarget диапазоны ─────────────────────────────────────────
        private const val IDLE_YAW_RANGE   = 3.0f   // ±3° при idle
        private const val IDLE_PITCH_RANGE = 2.0f   // ±2° при idle
        private const val SPEAK_YAW_RANGE  = 1.5f   // ±1.5° при речи (чаще смотрит в камеру)
        private const val SPEAK_PITCH_RANGE = 1.0f

        // ── CognitiveLook ─────────────────────────────────────────────────
        private const val COG_YAW_MAG   = 4.5f    // градусы в сторону
        private const val COG_PITCH_MAG = 3.0f    // градусы вверх (вспоминание)
        private const val COG_THRESHOLD = 0.20f

        // ── NodImpulse ────────────────────────────────────────────────────
        private const val NOD_IMPULSE       = -4.0f    // отрицательный: подбородок вниз
        private const val NOD_RETURN_SPEED  = 16f
        private const val NOD_FLUX_THRESHOLD = 0.28f
        private const val NOD_COOLDOWN_MIN   = 0.18f
        private const val NOD_COOLDOWN_MAX   = 0.30f

        // ── EmphasisYaw ───────────────────────────────────────────────────
        private const val EMPHASIS_MAG         = 4.5f
        private const val EMPHASIS_AROUSAL_THR = 0.42f
        private const val EMPHASIS_COOLDOWN_MIN = 0.9f
        private const val EMPHASIS_COOLDOWN_MAX = 1.6f

        // ── Постуральный sway (очень малая амплитуда) ─────────────────────
        private const val SWAY_PITCH_AMP = 0.35f   // градусы (было 1.6 в v3 — слишком много)
        private const val SWAY_YAW_AMP   = 0.40f
        private const val SWAY_ROLL_AMP  = 0.18f
        private const val BREATH_AMP     = 0.55f   // от дыхания

        // ── Saccade intervals (секунды) ───────────────────────────────────
        private const val SACCADE_SPEAK_MIN  = 0.9f
        private const val SACCADE_SPEAK_MAX  = 2.2f
        private const val SACCADE_IDLE_MIN   = 1.4f
        private const val SACCADE_IDLE_MAX   = 3.5f
        private const val SACCADE_COG_MIN    = 0.8f
        private const val SACCADE_COG_MAX    = 2.0f
        private const val SACCADE_COOLDOWN   = 0.12f  // пауза после смены цели

        // ── Вероятность зрительного контакта ─────────────────────────────
        private const val GAZE_CONTACT_PROB_SPEAKING = 0.92f  // 92% при речи
        private const val GAZE_CONTACT_PROB_IDLE     = 0.82f  // 82% при idle
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Шаг симуляции. Обновляет [pitch], [yaw], [roll].
     *
     * @param dtMs           delta time в мс
     * @param rms            текущий RMS (0..1)
     * @param arousal        возбуждение из ProsodyTracker (0..1)
     * @param thoughtfulness когнитивная нагрузка из ProsodyTracker (0..1)
     * @param isSpeaking     AI говорит (влияет на вероятность зрительного контакта)
     * @param flux           spectralFlux (0..1) — триггер кивков
     */
    fun update(
        dtMs:          Long,
        rms:           Float,
        arousal:       Float,
        thoughtfulness: Float,
        isSpeaking:    Boolean,
        flux:          Float,
    ) {
        val dt = dtMs.coerceIn(1, 32) / 1000f

        // ── 1. ПОСТУРАЛЬНЫЙ SWAY + ДЫХАНИЕ ───────────────────────────────
        val breathSpeed = 1f + arousal * 0.35f
        breathPhase += dt * 0.78f * breathSpeed   // ~4.8 сек цикл дыхания
        swayPhase1  += dt * 0.31f                 // несоизмеримые частоты
        swayPhase2  += dt * 0.47f
        swayPhase3  += dt * 0.37f

        // Дыхание: грудь расширяется → голова чуть назад (положительный pitch)
        val breathPitch = sin(breathPhase) * BREATH_AMP

        // Постуральный sway: очень малая амплитуда, апериодичен
        val swayPitch = (sin(swayPhase1) + sin(swayPhase3 * 1.27f) * 0.28f) * SWAY_PITCH_AMP
        val swayYaw   = (sin(swayPhase2) + cos(swayPhase3 * 0.71f) * 0.32f) * SWAY_YAW_AMP
        val swayRoll  = sin(swayPhase3 + swayPhase1 * 0.19f) * SWAY_ROLL_AMP

        // При активной речи sway почти подавлен (шея напряжена)
        val swayScale = if (isSpeaking) 0.15f else 1.0f

        // ── 2. SACCADE SYSTEM (Марковское блуждание фокуса) ───────────────
        saccadeTimer    -= dt
        saccadeCooldown  = (saccadeCooldown - dt).coerceAtLeast(0f)

        if (saccadeTimer <= 0f && saccadeCooldown <= 0f) {
            updateFocalTarget(thoughtfulness, isSpeaking)
            saccadeTimer    = randomSaccadeInterval(isSpeaking, thoughtfulness)
            saccadeCooldown = SACCADE_COOLDOWN
        }

        // ── 3. COGNITIVE LOOK ─────────────────────────────────────────────
        updateCognitiveLook(thoughtfulness, dt)

        // ── 4. NOD IMPULSE (flux-driven) ──────────────────────────────────
        nodCooldown = (nodCooldown - dt).coerceAtLeast(0f)

        if (isSpeaking && flux > NOD_FLUX_THRESHOLD && nodCooldown <= 0f) {
            val strength = (0.55f + flux * 3.5f).coerceAtMost(1.4f)
            nodOffset = NOD_IMPULSE * strength * (1f + arousal * 0.25f)
            nodCooldown = NOD_COOLDOWN_MIN +
                    Random.nextFloat() * (NOD_COOLDOWN_MAX - NOD_COOLDOWN_MIN)
        }
        // Упругий возврат кивка
        nodOffset += (0f - nodOffset) * NOD_RETURN_SPEED * dt

        // ── 5. EMPHASIS YAW (акцентирование при высоком Arousal) ──────────
        emphasisCooldown = (emphasisCooldown - dt).coerceAtLeast(0f)

        if (isSpeaking &&
            arousal > EMPHASIS_AROUSAL_THR &&
            emphasisCooldown <= 0f) {
            emphasisDir = -emphasisDir
            emphasisYaw = EMPHASIS_MAG * emphasisDir * arousal *
                    (0.65f + Random.nextFloat() * 0.35f)
            emphasisCooldown = EMPHASIS_COOLDOWN_MIN +
                    Random.nextFloat() * (EMPHASIS_COOLDOWN_MAX - EMPHASIS_COOLDOWN_MIN)
        }
        // Затухание emphasis (быстрее чем saccade)
        emphasisYaw *= (1f - dt * 4.5f).coerceAtLeast(0f)

        // ── 6. ИТОГОВЫЕ ЦЕЛИ ──────────────────────────────────────────────
        val targetPitch = (focalPitch + cogPitchTarget + nodOffset +
                (breathPitch + swayPitch) * swayScale)
            .coerceIn(-MAX_PITCH, MAX_PITCH)

        val targetYaw = (focalYaw + cogYawTarget + emphasisYaw +
                swayYaw * swayScale)
            .coerceIn(-MAX_YAW, MAX_YAW)

        // Анатомический roll: при повороте головы шея делает
        // компенсаторный наклон в ту же сторону (~15%)
        val targetRoll = (-targetYaw * 0.14f + swayRoll * swayScale)
            .coerceIn(-MAX_ROLL, MAX_ROLL)

        // ── 7. SPRING INTEGRATION ─────────────────────────────────────────
        pitchVel += ((targetPitch - pitch) * NECK_K - pitchVel * NECK_D) * dt
        yawVel   += ((targetYaw   - yaw)   * NECK_K - yawVel   * NECK_D) * dt
        rollVel  += ((targetRoll  - roll)  * NECK_K - rollVel  * NECK_D) * dt

        pitch = (pitch + pitchVel * dt).coerceIn(-MAX_PITCH, MAX_PITCH)
        yaw   = (yaw   + yawVel   * dt).coerceIn(-MAX_YAW,   MAX_YAW)
        roll  = (roll  + rollVel  * dt).coerceIn(-MAX_ROLL,   MAX_ROLL)
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

    // ═══════════════════════════════════════════════════════════════════════
    //  PRIVATE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Обновляет FocalTarget — куда аватар «смотрит».
     *
     * МАРКОВСКИЕ СОСТОЯНИЯ:
     *   CONTACT  → focalYaw ≈ 0, focalPitch ≈ 0  (зрительный контакт)
     *   WANDER   → случайная точка в пределах диапазона
     *   COGNITIVE → управляется updateCognitiveLook
     */
    private fun updateFocalTarget(thoughtfulness: Float, isSpeaking: Boolean) {
        // При высокой когнитивной нагрузке — взгляд управляется CognitiveLook
        if (thoughtfulness > COG_THRESHOLD) return

        val contactProb = if (isSpeaking)
            GAZE_CONTACT_PROB_SPEAKING else GAZE_CONTACT_PROB_IDLE

        if (Random.nextFloat() < contactProb) {
            // Зрительный контакт: почти прямо, небольшое случайное смещение
            focalYaw   = (Random.nextFloat() - 0.5f) * 1.8f
            focalPitch = (Random.nextFloat() - 0.5f) * 1.2f
        } else {
            // Блуждание взгляда
            val yawRange   = if (isSpeaking) SPEAK_YAW_RANGE   else IDLE_YAW_RANGE
            val pitchRange = if (isSpeaking) SPEAK_PITCH_RANGE  else IDLE_PITCH_RANGE
            focalYaw   = (Random.nextFloat() - 0.5f) * 2f * yawRange
            focalPitch = (Random.nextFloat() - 0.5f) * 2f * pitchRange
        }
    }

    /**
     * CognitiveLook — взгляд «в себя» при обдумывании.
     *
     * ФИЗИОЛОГИЯ:
     *   При активации рабочей памяти (вспоминание, формулирование)
     *   люди инстинктивно отводят взгляд вверх и в сторону.
     *   Направление (лево/право) фиксируется на входе в фазу и
     *   не меняется до выхода — аватар «думает в одном направлении».
     *
     * ВЫХОД из фазы:
     *   Когда thoughtfulness < COG_THRESHOLD, цели плавно возвращаются к 0.
     *   Spring в update() сделает переход органичным.
     */
    private fun updateCognitiveLook(thoughtfulness: Float, dt: Float) {
        if (thoughtfulness > COG_THRESHOLD) {
            if (!cogActive) {
                // Входим в фазу: выбираем направление один раз
                cogYawTarget   = if (Random.nextBoolean()) COG_YAW_MAG else -COG_YAW_MAG
                cogPitchTarget = COG_PITCH_MAG   // всегда вверх (вспоминание)
                cogActive = true
            }
            // Масштабируем по глубине задумчивости
            cogYawTarget   = cogYawTarget.let {
                val sign = if (it > 0f) 1f else -1f
                sign * COG_YAW_MAG * thoughtfulness
            }
            cogPitchTarget = COG_PITCH_MAG * thoughtfulness
        } else {
            // Выходим из фазы: плавное возвращение к нейтрали
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

    // ── Helpers ───────────────────────────────────────────────────────────

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