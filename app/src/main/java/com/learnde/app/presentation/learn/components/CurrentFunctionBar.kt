// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/CurrentFunctionBar.kt
//
// Инфо-табло внизу каждого экрана Learn-блока.
// Показывает в реальном времени какую функцию Gemini сейчас обрабатывает:
//   • IDLE        — серая полоска «Ожидание…»
//   • DETECTED    — пульсирующий оранжевый, имя функции
//   • EXECUTING   — бегущая синяя подсветка, "выполняется"
//   • COMPLETED   — зелёная (success) или красная (error) на 1.2с
//
// При concurrentCount > 1 справа значок "×N" с количеством параллельных.
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.learnde.app.learn.core.FunctionPhase
import com.learnde.app.learn.core.FunctionStatus

@Composable
fun CurrentFunctionBar(
    status: FunctionStatus,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, label, icon) = when (status.phase) {
        FunctionPhase.IDLE -> Quad(
            bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            fg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            label = "Gemini · ожидание",
            icon = Icons.Filled.RadioButtonUnchecked,
        )
        FunctionPhase.DETECTED -> Quad(
            bg = Color(0xFFFB8C00).copy(alpha = 0.20f),
            fg = Color(0xFFFB8C00),
            label = "DETECTED · ${status.functionName}",
            icon = Icons.Filled.Bolt,
        )
        FunctionPhase.EXECUTING -> Quad(
            bg = Color(0xFF1E88E5).copy(alpha = 0.22f),
            fg = Color(0xFF1E88E5),
            label = "EXECUTING · ${status.functionName}",
            icon = Icons.Filled.Bolt,
        )
        FunctionPhase.COMPLETED -> if (status.success) Quad(
            bg = Color(0xFF43A047).copy(alpha = 0.22f),
            fg = Color(0xFF43A047),
            label = "OK · ${status.functionName}",
            icon = Icons.Filled.Check,
        ) else Quad(
            bg = Color(0xFFE53935).copy(alpha = 0.22f),
            fg = Color(0xFFE53935),
            label = "ERR · ${status.functionName}",
            icon = Icons.Filled.Close,
        )
    }

    // Анимация пульсации для DETECTED/EXECUTING
    val pulse = rememberInfiniteTransition(label = "fnPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val isActive = status.phase == FunctionPhase.DETECTED ||
            status.phase == FunctionPhase.EXECUTING

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Левая «бегущая» подсветка для EXECUTING
        if (status.phase == FunctionPhase.EXECUTING) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.35f)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                fg.copy(alpha = 0.18f),
                                Color.Transparent,
                            )
                        )
                    )
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) fg.copy(alpha = alpha) else fg,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))

            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(180)))
                        .using(SizeTransform(clip = false))
                },
                label = "labelAnim"
            ) { text ->
                Text(
                    text = text,
                    fontSize = 12.sp,
                    color = fg,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            if (status.concurrentCount > 1) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(fg.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "×${status.concurrentCount}",
                        fontSize = 11.sp,
                        color = fg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private data class Quad(
    val bg: Color,
    val fg: Color,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)