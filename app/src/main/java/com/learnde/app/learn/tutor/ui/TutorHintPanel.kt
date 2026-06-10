package com.learnde.app.learn.tutor.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.learnde.app.learn.tutor.TutorHintCard
import com.learnde.app.learn.tutor.TutorHintType
import com.learnde.app.presentation.learn.theme.learnColors

@Composable
fun TutorHintPanel(
    cards: List<TutorHintCard>,
    unreadCount: Int,
    enabled: Boolean,
    maxExpandedFraction: Float = 0.4f,
    onExpandedChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    val colors = learnColors()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Новая карточка → плавный скролл к ней, если панель раскрыта.
    LaunchedEffect(cards.size) {
        if (expanded && cards.isNotEmpty()) {
            listState.animateScrollToItem(cards.lastIndex)
        }
    }
    LaunchedEffect(expanded) { onExpandedChanged(expanded) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(colors.surface)
            .animateContentSize(tween(240)),
    ) {
        // ── Шапка-чип (всегда видна) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (cards.isEmpty()) "Подсказки появятся по ходу урока"
                       else "Подсказки урока",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            if (!expanded && unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = unreadCount.coerceAtMost(9).toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.surface,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore
                              else Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        // ── Раскрытое содержимое ──
        if (expanded && cards.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(maxExpandedFraction),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    HintCardItem(card)
                }
            }
        }
    }
}

@Composable
private fun HintCardItem(card: TutorHintCard) {
    val colors = learnColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.background)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = card.type.icon(),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = card.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = card.body,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colors.textPrimary.copy(alpha = 0.85f),
        )
        if (card.examples.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            card.examples.forEach { ex ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = ex.de,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "— ${ex.ru}",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

private fun TutorHintType.icon(): ImageVector = when (this) {
    TutorHintType.GRAMMAR   -> Icons.Filled.MenuBook
    TutorHintType.ATTENTION -> Icons.Filled.PriorityHigh
    TutorHintType.VOCAB     -> Icons.Filled.Translate
    TutorHintType.CULTURE   -> Icons.Filled.Lightbulb
}