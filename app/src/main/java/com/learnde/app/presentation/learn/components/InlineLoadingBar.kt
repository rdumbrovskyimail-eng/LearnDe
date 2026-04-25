// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.1
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/InlineLoadingBar.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors
import kotlinx.coroutines.delay

/**
 * Компактный inline-loader без затемнения.
 * Высота 36dp, полная ширина (управляется через Modifier).
 */
@Composable
fun InlineLoadingBar(
    modifier: Modifier = Modifier,
) {
    val colors = learnColors()

    val texts = listOf(
        "Генерация ситуаций…",
        "Загрузка инструментов…",
        "Подготовка сценария…",
        "Подключение к Gemini…",
    )

    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            idx = (idx + 1) % texts.size
        }
    }

    val pulse = rememberInfiniteTransition(label = "loaderPulse")
    val dotScale by pulse.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.accentSoft)
            .border(
                LearnTokens.BorderThin,
                colors.accent.copy(alpha = 0.18f),
                RoundedCornerShape(LearnTokens.RadiusXs),
            )
            .padding(horizontal = LearnTokens.PaddingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(dotScale)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(LearnTokens.PaddingSm))
        AnimatedContent(
            targetState = texts[idx],
            transitionSpec = {
                fadeIn(tween(450)) togetherWith fadeOut(tween(350))
            },
            label = "loaderText",
        ) { txt ->
            Text(
                text = txt,
                fontSize = LearnTokens.FontSizeCaption,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
        }
    }
}