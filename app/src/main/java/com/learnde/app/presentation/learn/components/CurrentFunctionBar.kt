// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/CurrentFunctionBar.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.learnde.app.learn.core.FunctionPhase
import com.learnde.app.learn.core.FunctionStatus
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

@Composable
fun CurrentFunctionBar(
    status: FunctionStatus,
    modifier: Modifier = Modifier,
) {
    val colors = learnColors()

    data class Style(val bg: Color, val fg: Color, val label: String, val showRunning: Boolean)

    val style = when (status.phase) {
        FunctionPhase.IDLE -> Style(
            bg = colors.surfaceVar,
            fg = colors.textLow,
            label = "Gemini · ожидание",
            showRunning = false,
        )
        FunctionPhase.DETECTED -> Style(
            bg = colors.warnSoft,
            fg = colors.warn,
            label = "Готовится: ${status.functionName}",
            showRunning = false,
        )
        FunctionPhase.EXECUTING -> Style(
            bg = colors.accentSoft,
            fg = colors.accent,
            label = "Выполняется: ${status.functionName}",
            showRunning = true,
        )
        FunctionPhase.COMPLETED -> if (status.success) Style(
            bg = colors.successSoft,
            fg = colors.success,
            label = "Готово: ${status.functionName}",
            showRunning = false,
        ) else Style(
            bg = colors.errorSoft,
            fg = colors.error,
            label = "Ошибка: ${status.functionName}",
            showRunning = false,
        )
    }

    val pulse = rememberInfiniteTransition(label = "fnPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val active = status.phase == FunctionPhase.DETECTED ||
        status.phase == FunctionPhase.EXECUTING

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(style.bg)
            .padding(horizontal = LearnTokens.PaddingMd),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (style.showRunning) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                style.fg.copy(alpha = 0.18f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (active) style.fg.copy(alpha = pulseAlpha) else style.fg),
            )
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            AnimatedContent(
                targetState = style.label,
                transitionSpec = {
                    (fadeIn(tween(200)) togetherWith fadeOut(tween(150)))
                        .using(SizeTransform(clip = false))
                },
                label = "labelAnim",
            ) { text ->
                Text(
                    text = text,
                    fontSize = 10.sp,
                    color = style.fg,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (status.concurrentCount > 1) {
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(style.fg.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${status.concurrentCount}",
                        fontSize = 9.sp,
                        color = style.fg,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
