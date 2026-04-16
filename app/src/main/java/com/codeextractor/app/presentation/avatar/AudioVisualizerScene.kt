// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/codeextractor/app/presentation/avatar/AudioVisualizerScene.kt
//
// Пульсирующий чёрный экран с радужной анимацией, реагирующей
// на амплитуду аудио-потока модели (playbackSync).
// Используется как альтернатива AvatarScene в режиме SceneMode.VISUALIZER.
//
// Принципы:
//  - rms рассчитывается из 16-bit PCM chunk'ов
//  - hue пробегает 0..360 со скоростью, зависящей от rms
//  - 6 концентрических колец с плавным радиусом и прозрачностью
//  - Всё на GPU через Canvas, 60 fps стабильно на Snapdragon 6 Gen 1+
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.avatar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun AudioVisualizerScene(
    modifier: Modifier = Modifier,
    playbackSync: Flow<ByteArray>
) {
    // ── Текущее RMS (0..1) аудио ──
    var rms by remember { mutableFloatStateOf(0f) }
    var lastDecayNanos by remember { mutableLongStateOf(0L) }

    // Плавное затухание RMS + оживлённый hue
    val animatedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f),
        label = "rmsAnim"
    )

    // Подписка на поток модели
    LaunchedEffect(playbackSync) {
        playbackSync.collect { pcm ->
            rms = computeRms16(pcm).coerceIn(0f, 1f)
            lastDecayNanos = System.nanoTime()
        }
    }

    // Плавное затухание при тишине — независимо от частоты кадров.
    LaunchedEffect(Unit) {
        var prevFrame = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prevFrame).coerceAtLeast(0L) / 1_000_000_000f
                prevFrame = now
                val silenceNanos = now - lastDecayNanos
                if (silenceNanos > 120_000_000L) {
                    // Экспоненциальное затухание: за 1 секунду ~98% погашения.
                    val decay = Math.pow(0.02, dt.toDouble()).toFloat()
                    rms = max(0f, rms * decay)
                }
            }
        }
    }

    // Hue «вращается» быстрее при громком звуке
    var hue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev) / 1_000_000_000f
                prev = now
                val speed = 30f + animatedRms * 240f // deg/sec
                hue = (hue + speed * dt) % 360f
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val baseR = min(w, h) / 2f
            // При молчании кольца «сжимаются» к центру, при звуке — расширяются
            val pulse = 0.25f + animatedRms * 0.75f

            val rings = 6
            for (i in 0 until rings) {
                val t = i.toFloat() / rings
                val ringHue = (hue + t * 180f) % 360f
                val color = hsv(ringHue, 0.95f, 1f).copy(alpha = 0.18f + (1f - t) * 0.42f)
                val r = baseR * (0.22f + t * 0.78f) * pulse
                drawCircle(
                    color = color,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = (8f + (1f - t) * 14f) * (0.6f + pulse * 0.8f))
                )
            }
            // Центральное свечение — рассеивает «мёртвую» точку
            val glowBrush = Brush.radialGradient(
                colors = listOf(
                    hsv(hue, 0.9f, 1f).copy(alpha = 0.35f + animatedRms * 0.55f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = baseR * (0.4f + pulse * 0.6f)
            )
            drawCircle(brush = glowBrush, radius = baseR * pulse, center = Offset(cx, cy))
        }
    }
}

// ─────────────────────────────────────────────
// Math helpers
// ─────────────────────────────────────────────

/** RMS из little-endian 16-bit PCM. Нормировано в 0..1. */
private fun computeRms16(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    val n = pcm.size / 2
    var sum = 0.0
    var i = 0
    while (i + 1 < pcm.size) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt() // signed
        val sample = (hi shl 8) or lo
        // sign-extend 16-bit
        val s = if (sample >= 0x8000) sample - 0x10000 else sample
        sum += (s * s).toDouble()
        i += 2
    }
    val rms = sqrt(sum / n) / 32768.0
    // Нелинейное усиление для «отзывчивости» — log-like
    return (rms * 2.8).toFloat().coerceIn(0f, 1f)
}

/** HSV → Color (h: 0..360, s,v: 0..1) */
private fun hsv(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val hp = h / 60f
    val x = c * (1f - kotlin.math.abs((hp % 2f) - 1f))
    val (r, g, b) = when {
        hp < 1 -> Triple(c, x, 0f)
        hp < 2 -> Triple(x, c, 0f)
        hp < 3 -> Triple(0f, c, x)
        hp < 4 -> Triple(0f, x, c)
        hp < 5 -> Triple(x, 0f, c)
        else   -> Triple(c, 0f, x)
    }
    val m = v - c
    return Color(r + m, g + m, b + m, 1f)
}