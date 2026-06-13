package com.learnde.app.presentation.learn.v2.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.learnde.app.presentation.learn.v2.GeminiGradients
import com.learnde.app.presentation.learn.v2.GeminiTokens
import kotlin.math.min

enum class OrbMode { IDLE, SPEAKING, LISTENING, CONNECTING }

@Composable
fun GeminiOrb(
    mode: OrbMode,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    // Базовая «дыхательная» пульсация масштаба.
    val breathe by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    // Быстрая пульсация во время речи ИИ.
    val speakPulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakPulse",
    )

    // Лёгкая высокочастотная дрожь при прослушивании.
    val listenJitter by transition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listenJitter",
    )

    // Угол вращающейся дуги соединения.
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    // Прозрачность свечения.
    val glowAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val scale = when (mode) {
        OrbMode.IDLE -> breathe
        OrbMode.SPEAKING -> speakPulse
        OrbMode.LISTENING -> listenJitter
        OrbMode.CONNECTING -> breathe
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val canvas = this.size.minDimension
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val coreRadius = canvas * 0.30f * scale

            // ── Внешнее свечение (под состояние) ──
            val glowBrush: Brush? = when (mode) {
                OrbMode.SPEAKING -> GeminiGradients.speakingGlow()
                OrbMode.LISTENING -> GeminiGradients.listeningGlow()
                OrbMode.IDLE, OrbMode.CONNECTING -> null
            }
            if (glowBrush != null) {
                drawCircle(
                    brush = glowBrush,
                    radius = canvas * 0.5f,
                    center = center,
                    alpha = glowAlpha,
                )
            } else {
                // Тонкое статичное гало для idle/connecting.
                drawCircle(
                    brush = GeminiGradients.orbRadial(),
                    radius = canvas * 0.42f,
                    center = center,
                    alpha = 0.12f,
                )
            }

            // ── Ядро-шар (радиальный градиент) ──
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GeminiTokens.Blue400,
                        GeminiTokens.Blue600,
                        GeminiTokens.Blue700,
                    ),
                    center = Offset(
                        center.x - coreRadius * 0.3f,
                        center.y - coreRadius * 0.3f,
                    ),
                    radius = coreRadius * 1.6f,
                ),
                radius = coreRadius,
                center = center,
            )

            // ── Блик ──
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = coreRadius * 0.28f,
                center = Offset(
                    center.x - coreRadius * 0.34f,
                    center.y - coreRadius * 0.36f,
                ),
            )

            // ── Дуга соединения ──
            if (mode == OrbMode.CONNECTING) {
                val arcRadius = canvas * 0.40f
                val stroke = Stroke(width = canvas * 0.03f, cap = StrokeCap.Round)
                drawArc(
                    color = GeminiTokens.Blue300,
                    startAngle = sweepAngle,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
                    style = stroke,
                )
            }

            // ── Тонкое внешнее кольцо-обводка ──
            drawCircle(
                color = GeminiTokens.Blue300.copy(alpha = 0.25f),
                radius = min(coreRadius * 1.18f, canvas * 0.46f),
                center = center,
                style = Stroke(width = canvas * 0.006f),
            )
        }
    }
}