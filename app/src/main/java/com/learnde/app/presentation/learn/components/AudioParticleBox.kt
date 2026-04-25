// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/AudioParticleBox.kt
//
// Компактный квадратный визуализатор реакции на голос Gemini.
// При громкой речи — много шариков "выстреливают" по стенкам внутри box'а
// (физика отскока + гравитация выключена).
// При тишине — шарики падают на дно (гравитация включена, всё оседает).
//
// Используется в каждом бар Learn-блока вместо AudioVisualizerScene
// (там был fullscreen rainbow). Здесь — минимализм, монохром, аккуратность.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Визуализатор-квадратик 32-40dp с шариками, реагирующий на playbackSync.
 *
 * @param playbackSync поток PCM-аудио от Gemini.
 * @param size размер квадрата (по умолчанию 36dp).
 * @param accent основной цвет шариков (если null — берёт LearnColors.accent).
 */
@Composable
fun AudioParticleBox(
    playbackSync: Flow<ByteArray>,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    accent: Color? = null,
) {
    val colors = learnColors()
    val ballColor = accent ?: colors.accent

    // ── 1. RMS из PCM ──
    var rms by remember { mutableFloatStateOf(0f) }
    var lastSoundNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playbackSync) {
        playbackSync.collect { pcm ->
            rms = computeRms16(pcm)
            lastSoundNanos = System.nanoTime()
        }
    }

    // ── 2. Затухание RMS при тишине ──
    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now
                if (now - lastSoundNanos > 100_000_000L) {
                    val decay = Math.pow(0.05, dt.toDouble()).toFloat()
                    rms = max(0f, rms * decay)
                }
            }
        }
    }

    // ── 3. Физика частиц ──
    val particleCount = 22
    val particles = remember {
        Array(particleCount) {
            Particle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                vx = (Random.nextFloat() - 0.5f) * 0.4f,
                vy = (Random.nextFloat() - 0.5f) * 0.4f,
                radius = 0.04f + Random.nextFloat() * 0.04f,
            )
        }
    }

    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - prev).coerceAtLeast(0L) / 1_000_000_000f).coerceAtMost(0.05f)
                prev = now
                stepPhysics(particles, rms, dt)
                tick = now
            }
        }
    }

    // ── 4. Рендер ──
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.surfaceVar)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusXs))
    ) {
        // tick читается, чтобы Compose инвалидировал Canvas
        @Suppress("UNUSED_EXPRESSION") tick
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            particles.forEach { p ->
                val cx = p.x * w
                val cy = p.y * h
                val rPx = p.radius * min(w, h)
                // Лёгкая прозрачность зависит от скорости — летающие ярче, лежащие тусклее
                val speed = sqrt(p.vx * p.vx + p.vy * p.vy)
                val alpha = (0.35f + speed * 2f).coerceIn(0.35f, 1f)
                drawCircle(
                    color = ballColor.copy(alpha = alpha),
                    radius = rPx,
                    center = Offset(cx, cy),
                )
            }
            // Тонкая линия "пола" — едва заметная, чтобы было понятно, куда оседают шарики
            drawLine(
                color = ballColor.copy(alpha = 0.15f),
                start = Offset(0f, h - 0.5f),
                end = Offset(w, h - 0.5f),
                strokeWidth = 1f,
            )
        }
    }
}

private class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    val radius: Float,
)

/**
 * Шаг физики частиц.
 *
 * intensity ∈ [0,1] — текущая громкость:
 *   - intensity > 0.05  → турбулентность, шарики получают случайные толчки,
 *                         стенки отскакивают со 100%.
 *   - intensity ≤ 0.05  → гравитация на дно, трение, оседание.
 */
private fun stepPhysics(particles: Array<Particle>, intensity: Float, dt: Float) {
    val active = intensity > 0.05f
    val turbulence = intensity * 4f
    val gravity = if (active) 0f else 2.4f
    val friction = if (active) 0f else 0.85f
    val damping = if (active) 0.9f else 0.5f

    particles.forEach { p ->
        // Случайный «выстрел» при громкой речи
        if (active && Random.nextFloat() < intensity * 0.15f) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
            val power = (0.6f + Random.nextFloat() * 0.6f) * turbulence * 0.3f
            p.vx += kotlin.math.cos(angle) * power
            p.vy += kotlin.math.sin(angle) * power
        }
        // Гравитация (только в тишине)
        p.vy += gravity * dt

        // Интегрируем
        p.x += p.vx * dt
        p.y += p.vy * dt

        // Стенки — отскок
        if (p.x - p.radius < 0f) {
            p.x = p.radius
            p.vx = -p.vx * damping
        }
        if (p.x + p.radius > 1f) {
            p.x = 1f - p.radius
            p.vx = -p.vx * damping
        }
        if (p.y - p.radius < 0f) {
            p.y = p.radius
            p.vy = -p.vy * damping
        }
        if (p.y + p.radius > 1f) {
            p.y = 1f - p.radius
            p.vy = -p.vy * damping
            // Трение о пол при оседании
            if (!active) p.vx *= friction
        }

        // Глобальное затухание скорости при тишине
        if (!active) {
            p.vx *= (1f - dt * 1.5f)
            p.vy *= (1f - dt * 0.5f)
            // Приклеиваем еле-движущиеся к полу
            if (kotlin.math.abs(p.vx) < 0.01f && kotlin.math.abs(p.vy) < 0.05f) {
                p.vx = 0f
                p.vy = 0f
            }
        }
    }
}

/** RMS из little-endian 16-bit PCM. Нормировано в 0..1. */
private fun computeRms16(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    val n = pcm.size / 2
    var sum = 0.0
    var i = 0
    while (i + 1 < pcm.size) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        val sample = (hi shl 8) or lo
        val s = if (sample >= 0x8000) sample - 0x10000 else sample
        sum += (s * s).toDouble()
        i += 2
    }
    val rms = sqrt(sum / n) / 32768.0
    return (rms * 2.8).toFloat().coerceIn(0f, 1f)
}
