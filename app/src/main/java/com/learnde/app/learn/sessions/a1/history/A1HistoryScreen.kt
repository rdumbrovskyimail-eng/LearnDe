// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 2.5)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/A1HistoryScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.History, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("История уроков", fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
        ) {
            // ═══ Статистика-шапка ═══
            StatsHeader(
                totalCount = state.totalCount,
                thisWeekCount = state.thisWeekCount,
                avgQuality = state.avgQualityRecent,
            )

            Spacer(Modifier.height(12.dp))

            // ═══ Фильтры ═══
            FilterRow(
                current = state.filter,
                onChange = { vm.onIntent(A1HistoryIntent.ChangeFilter(it)) }
            )

            Spacer(Modifier.height(8.dp))

            // ═══ Список сессий ═══
            val items = vm.filteredItems()
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Загрузка...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (items.isEmpty()) {
                EmptyState(filter = state.filter)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.entity.id }) { item ->
                        SessionCard(
                            item = item,
                            onRepeat = { vm.onIntent(A1HistoryIntent.RepeatCluster(item.entity.clusterId)) },
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
                        Icons.Filled.Warning,
                        null,
                        tint = Color(0xFFFB8C00),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить запись?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Это удалит запись из истории. " +
                    "ВНИМАНИЕ: прогресс по словам и грамматике, полученный в этой сессии, " +
                    "сохранится — алгоритм FSRS не откатывает интервалы повторения.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = sessionToDelete
                        sessionToDelete = null
                        if (id != null) {
                            vm.onIntent(A1HistoryIntent.DeleteSession(id))
                        }
                    }
                ) {
                    Text("Удалить", color = Color(0xFFE53935), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun StatsHeader(totalCount: Int, thisWeekCount: Int, avgQuality: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatBox("Всего", "$totalCount", MaterialTheme.colorScheme.primary)
        StatBox("За неделю", "$thisWeekCount", Color(0xFF43A047))
        StatBox(
            "Средний балл",
            if (avgQuality > 0) String.format(Locale.ROOT, "%.1f", avgQuality) else "—",
            Color(0xFFFB8C00)
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = color.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FilterRow(current: HistoryFilter, onChange: (HistoryFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HistoryFilter.entries.forEach { f ->
            val label = when (f) {
                HistoryFilter.ALL -> "Все"
                HistoryFilter.COMPLETE -> "Завершённые"
                HistoryFilter.INCOMPLETE -> "Прерванные"
                HistoryFilter.THIS_WEEK -> "Эта неделя"
            }
            FilterChip(
                selected = current == f,
                onClick = { onChange(f) },
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                )
            )
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
    val entity = item.entity
    val completeColor = if (entity.isComplete) Color(0xFF43A047) else Color(0xFFFB8C00)
    val qualityColor = when (entity.overallQuality) {
        in 6..7 -> Color(0xFF43A047)
        in 4..5 -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Левая колонка: статус-иконка + quality-балл
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(qualityColor),
                contentAlignment = Alignment.Center
            ) {
                Text("${entity.overallQuality}", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(4.dp))
            Icon(
                if (entity.isComplete) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = completeColor,
                modifier = Modifier.size(14.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        // Центр: название + метаданные
        Column(Modifier.weight(1f)) {
            Text(item.clusterTitleRu, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (item.clusterTitleDe.isNotBlank()) {
                Text(item.clusterTitleDe, fontSize = 11.sp,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Row {
                Text(formatDate(entity.startedAt), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" · ", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${entity.durationMinutes} мин", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Row {
                MiniStat("✓", entity.lemmasProducedJson.lemmaCount(), Color(0xFF43A047))
                Spacer(Modifier.width(8.dp))
                MiniStat("✗", entity.lemmasFailedJson.lemmaCount(), Color(0xFFE53935))
                if (!entity.isComplete) {
                    Spacer(Modifier.width(8.dp))
                    Text("до ${entity.phaseReached}", fontSize = 10.sp,
                        color = Color(0xFFFB8C00), fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Правая колонка: кнопки
        Column {
            IconButton(onClick = onRepeat, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.PlayArrow, "Повторить",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, "Удалить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MiniStat(symbol: String, count: Int, color: Color) {
    Text("$symbol $count", fontSize = 11.sp, fontWeight = FontWeight.Medium,
        color = color, fontFamily = FontFamily.Monospace)
}

@Composable
private fun EmptyState(filter: HistoryFilter) {
    val msg = when (filter) {
        HistoryFilter.ALL -> "Ещё нет пройденных уроков"
        HistoryFilter.COMPLETE -> "Нет завершённых сессий"
        HistoryFilter.INCOMPLETE -> "Нет прерванных сессий"
        HistoryFilter.THIS_WEEK -> "На этой неделе занятий не было"
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.History, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(8.dp))
            Text(msg, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDate(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "только что"
        diff < 3_600_000 -> "${diff / 60_000} мин назад"
        diff < 86_400_000 -> "${diff / 3_600_000} ч назад"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} дн назад"
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ts))
    }
}

/** Количество лемм в JSON-массиве без полной десериализации. */
private fun String.lemmaCount(): Int =
    if (isBlank() || this == "[]") 0
    else count { it == ',' } + 1