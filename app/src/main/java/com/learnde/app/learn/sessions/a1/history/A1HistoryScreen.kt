// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/A1HistoryScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.Plural
import com.learnde.app.presentation.learn.theme.learnColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1HistoryScreen(
    onBack: () -> Unit,
    onRepeatCluster: (String) -> Unit,
    onOpenDetails: (Long) -> Unit,
    vm: A1HistoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = learnColors()
    var sessionToDelete by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                is A1HistoryEffect.NavigateToCluster -> onRepeatCluster(effect.clusterId)
                is A1HistoryEffect.NavigateToDetails -> onOpenDetails(effect.sessionId)
                is A1HistoryEffect.ShowToast ->
                    Toast.makeText(context, effect.msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.History, null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "История уроков",
                            fontSize = LearnTokens.FontSizeTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textHi,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Назад",
                            tint = colors.textHi,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingMd),
        ) {
            StatsHeader(
                totalCount = state.totalCount,
                thisWeekCount = state.thisWeekCount,
                avgQuality = state.avgQualityRecent,
            )

            Spacer(Modifier.height(LearnTokens.PaddingMd))

            FilterRow(
                current = state.filter,
                onChange = { vm.onIntent(A1HistoryIntent.ChangeFilter(it)) },
            )

            Spacer(Modifier.height(LearnTokens.PaddingSm))

            val items = vm.filteredItems()
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Загрузка…", color = colors.textLow)
                }
            } else if (items.isEmpty()) {
                EmptyState(filter = state.filter)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.entity.id }) { item ->
                        SessionCard(
                            item = item,
                            onRepeat = {
                                vm.onIntent(A1HistoryIntent.RepeatCluster(item.entity.clusterId))
                            },
                            onDelete = { sessionToDelete = item.entity.id },
                            onClick = { vm.onIntent(A1HistoryIntent.OpenDetails(item.entity.id)) },
                        )
                    }
                }
            }
        }
    }

    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        null,
                        tint = colors.warn,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(LearnTokens.PaddingSm))
                    Text("Удалить запись?", fontWeight = FontWeight.Bold, color = colors.textHi)
                }
            },
            text = {
                Text(
                    "Запись уйдёт из истории. Прогресс по словам и грамматике сохранится — алгоритм FSRS не откатывает интервалы повторения.",
                    fontSize = LearnTokens.FontSizeBody,
                    lineHeight = 18.sp,
                    color = colors.textMid,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = sessionToDelete
                        sessionToDelete = null
                        if (id != null) vm.onIntent(A1HistoryIntent.DeleteSession(id))
                    },
                ) {
                    Text("Удалить", color = colors.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Отмена", color = colors.textMid)
                }
            },
            containerColor = colors.surface,
            shape = RoundedCornerShape(LearnTokens.RadiusLg),
        )
    }
}

@Composable
private fun StatsHeader(totalCount: Int, thisWeekCount: Int, avgQuality: Float) {
    val colors = learnColors()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm),
    ) {
        StatBox("Всего", "$totalCount", colors.accent, Modifier.weight(1f))
        StatBox("За неделю", "$thisWeekCount", colors.success, Modifier.weight(1f))
        StatBox(
            "Средний балл",
            if (avgQuality > 0) String.format(Locale.ROOT, "%.1f", avgQuality) else "—",
            colors.warn,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    val colors = learnColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusSm))
            .padding(horizontal = LearnTokens.PaddingMd, vertical = 8.dp),
    ) {
        Text(
            value,
            fontSize = LearnTokens.FontSizeTitle,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            label,
            fontSize = LearnTokens.FontSizeMicro,
            color = colors.textLow,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FilterRow(current: HistoryFilter, onChange: (HistoryFilter) -> Unit) {
    val colors = learnColors()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(HistoryFilter.entries.toList()) { f ->
            val label = when (f) {
                HistoryFilter.ALL -> "Все"
                HistoryFilter.COMPLETE -> "Завершённые"
                HistoryFilter.INCOMPLETE -> "Прерванные"
                HistoryFilter.THIS_WEEK -> "Эта неделя"
            }
            val selected = current == f
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(LearnTokens.RadiusXs))
                    .background(if (selected) colors.accentSoft else colors.surface)
                    .border(
                        LearnTokens.BorderThin,
                        if (selected) colors.accent.copy(alpha = 0.4f) else colors.stroke,
                        RoundedCornerShape(LearnTokens.RadiusXs),
                    )
                    .clickable { onChange(f) }
                    .padding(horizontal = LearnTokens.PaddingMd, vertical = 6.dp),
            ) {
                Text(
                    label,
                    fontSize = LearnTokens.FontSizeCaption,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.accent else colors.textMid,
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    item: SessionHistoryItem,
    onRepeat: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = learnColors()
    val entity = item.entity
    val completeColor = if (entity.isComplete) colors.success else colors.warn
    val qualityColor = when {
        entity.overallQuality >= 6 -> colors.success
        entity.overallQuality >= 4 -> colors.warn
        else -> colors.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusSm))
            .clickable { onClick() }
            .padding(horizontal = LearnTokens.PaddingMd, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(qualityColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${entity.overallQuality}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = LearnTokens.FontSizeBody,
                )
            }
            Spacer(Modifier.height(2.dp))
            Icon(
                if (entity.isComplete) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = completeColor,
                modifier = Modifier.size(12.dp),
            )
        }

        Spacer(Modifier.width(LearnTokens.PaddingMd))

        Column(Modifier.weight(1f)) {
            Text(
                item.clusterTitleRu.ifBlank { "Повторение слабых слов" },
                fontSize = LearnTokens.FontSizeBody,
                fontWeight = FontWeight.SemiBold,
                color = colors.textHi,
            )
            if (item.clusterTitleDe.isNotBlank()) {
                Text(
                    item.clusterTitleDe,
                    fontSize = 11.sp,
                    color = colors.textMid,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row {
                Text(
                    com.learnde.app.learn.sessions.a1.util.A1DateFormatters.formatShortDate(entity.startedAt),
                    fontSize = 10.sp,
                    color = colors.textLow,
                )
                Text(" · ", fontSize = 10.sp, color = colors.textLow)
                val mins = entity.durationMinutes
                Text(
                    if (mins == 0) "<1 мин" else "$mins ${Plural.minute(mins)}",
                    fontSize = 10.sp,
                    color = colors.textLow,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniStat(Icons.Filled.CheckCircle, entity.lemmasProducedJson.lemmaCount(), colors.success)
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                MiniStat(Icons.Filled.Cancel, entity.lemmasFailedJson.lemmaCount(), colors.error)
                if (!entity.isComplete) {
                    Spacer(Modifier.width(LearnTokens.PaddingSm))
                    Text(
                        "до ${entity.phaseReached}",
                        fontSize = 10.sp,
                        color = colors.warn,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        IconButton(onClick = onRepeat, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.PlayArrow,
                "Повторить",
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                "Удалить",
                tint = colors.textLow,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, 
            contentDescription = null, 
            tint = color, 
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$count",
            fontSize = LearnTokens.FontSizeCaption,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun EmptyState(filter: HistoryFilter) {
    val colors = learnColors()
    val msg = when (filter) {
        HistoryFilter.ALL -> "Ещё нет пройденных уроков"
        HistoryFilter.COMPLETE -> "Нет завершённых сессий"
        HistoryFilter.INCOMPLETE -> "Нет прерванных сессий"
        HistoryFilter.THIS_WEEK -> "На этой неделе занятий не было"
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.History,
                null,
                tint = colors.textLow.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(LearnTokens.PaddingSm))
            Text(
                msg,
                fontSize = LearnTokens.FontSizeBody,
                color = colors.textLow,
            )
        }
    }
}

private fun String.lemmaCount(): Int =
    if (isBlank() || this == "[]") 0
    else count { it == ',' } + 1
