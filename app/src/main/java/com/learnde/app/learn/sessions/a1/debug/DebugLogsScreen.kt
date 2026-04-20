// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 4)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/debug/DebugLogsScreen.kt
//
// Экран просмотра логов прямо в приложении.
// Фильтры по уровню, автоскролл, копирование в буфер обмена.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.learnde.app.util.LogBuffer
import com.learnde.app.util.LogEntry
import com.learnde.app.util.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DebugLogsViewModel @Inject constructor(
    val buffer: LogBuffer,
) : ViewModel() {
    fun clear() = buffer.clear()
    fun export(): String = buffer.exportAsText()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogsScreen(
    onBack: () -> Unit,
    vm: DebugLogsViewModel = hiltViewModel(),
) {
    val allEntries by vm.buffer.entries.collectAsState()
    val context = LocalContext.current

    var activeLevels by remember { mutableStateOf(setOf(LogLevel.D, LogLevel.I, LogLevel.W, LogLevel.E)) }
    var search by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val filtered = remember(allEntries, activeLevels, search) {
        allEntries.filter { entry ->
            entry.level in activeLevels &&
                (search.isBlank() || entry.message.contains(search, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("Логи (${filtered.size}/${allEntries.size})",
                    fontSize = 15.sp, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            Icons.Filled.ArrowDownward, "Автоскролл",
                            tint = if (autoScroll) Color(0xFF43A047) else Color.Gray
                        )
                    }
                    IconButton(onClick = {
                        copyToClipboard(context, vm.export())
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, "Копировать", tint = Color.White)
                    }
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Filled.Delete, "Очистить", tint = Color(0xFFE53935))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0A0A)
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp)
        ) {
            // Фильтры по уровню
            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LevelChip("D", LogLevel.D, Color(0xFF9E9E9E), activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.D)
                }
                LevelChip("I", LogLevel.I, Color(0xFF64B5F6), activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.I)
                }
                LevelChip("W", LogLevel.W, Color(0xFFFB8C00), activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.W)
                }
                LevelChip("E", LogLevel.E, Color(0xFFE53935), activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.E)
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Логов пока нет", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.timestamp.toString() + it.message.hashCode() }) { entry ->
                        LogLine(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(
    label: String,
    level: LogLevel,
    color: Color,
    active: Set<LogLevel>,
    onClick: () -> Unit,
) {
    val isSelected = level in active
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = if (isSelected) color else Color.Gray)
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.2f),
            containerColor = Color(0xFF1A1A1A),
        )
    )
}

@Composable
private fun LogLine(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.D -> Color(0xFF9E9E9E)
        LogLevel.I -> Color(0xFF64B5F6)
        LogLevel.W -> Color(0xFFFB8C00)
        LogLevel.E -> Color(0xFFE53935)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(
                if (entry.level == LogLevel.E) Color(0xFFE53935).copy(alpha = 0.1f)
                else Color.Transparent
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = entry.formatted(),
            fontSize = 10.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            lineHeight = 13.sp,
        )
    }
}

private fun toggleLevel(set: Set<LogLevel>, level: LogLevel): Set<LogLevel> =
    if (level in set) set - level else set + level

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("logs", text))
}