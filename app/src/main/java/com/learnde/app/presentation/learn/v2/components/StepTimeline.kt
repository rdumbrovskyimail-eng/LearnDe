package com.learnde.app.presentation.learn.v2.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.learnde.app.presentation.learn.theme.LearnColors

enum class StepDotStatus { DONE, ACTIVE, PENDING, SKIPPED }

data class StepDot(
    val index: Int,
    val status: StepDotStatus,
    /** Короткая подпись типа шага для accessibility / tooltip. */
    val label: String = "",
)

@Composable
fun StepTimeline(
    dots: List<StepDot>,
    colors: LearnColors,
    modifier: Modifier = Modifier,
) {
    if (dots.isEmpty()) return

    val listState = rememberLazyListState()
    val activeIndex = dots.indexOfFirst { it.status == StepDotStatus.ACTIVE }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            val target = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    val pulse by rememberInfiniteTransition(label = "stepPulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stepPulseScale",
    )

    LazyRow(
        state = listState,
        modifier = modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) {
        itemsIndexed(dots, key = { _, d -> d.index }) { i, dot ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(dot = dot, colors = colors, pulse = pulse)
                // Соединитель между точками (кроме последней).
                if (i < dots.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = 10.dp)
                            .width(0.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Dot(dot: StepDot, colors: LearnColors, pulse: Float) {
    when (dot.status) {
        StepDotStatus.DONE -> Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )

        StepDotStatus.ACTIVE -> Box(
            Modifier
                .scale(pulse)
                .size(14.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .border(2.dp, colors.accent.copy(alpha = 0.35f), CircleShape)
        )

        StepDotStatus.PENDING -> Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.18f))
        )

        StepDotStatus.SKIPPED -> Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .border(1.5.dp, colors.textTertiary, CircleShape)
        )
    }
}