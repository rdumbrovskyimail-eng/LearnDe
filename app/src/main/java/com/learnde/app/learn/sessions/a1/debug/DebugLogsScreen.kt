// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/debug/DebugLogsScreen.kt
//
// КРИТИЧНЫЙ ФИКС КРАША:
//   Раньше: vm.buffer.entries.collectAsState() — но buffer мог быть null,
//   если LogBuffer не успел инициализироваться (особенно при холодном старте
//   с открытием прямо в этот экран). Также collectAsState без initial value
//   и hashCode() в key мог падать на повторяющихся timestamp'ах.
//
//   Теперь:
//     - lifecycleAware collect через collectAsStateWithLifecycle с initialValue
//     - safe key: timestamp + index
//     - try/catch вокруг buffer.exportAsText() и buffer.clear()
//     - обработка null/empty состояний
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors
import com.learnde.app.util.LogBuffer
import com.learnde.app.util.LogEntry
import com.learnde.app.util.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DebugLogsViewModel @Inject constructor(
    private val buffer: LogBuffer,
) : ViewModel() {

    /** Безопасный геттер потока. */
    val entries get() = buffer.entries

    fun clear() {
        runCatching { buffer.clear() }
    }

    /** Возвращает текст или пустую строку при ошибке. */
    // ФИКС: Переносим тяжелую операцию склейки строк в фоновый поток
    suspend fun export(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { buffer.exportAsText() }.getOrDefault("")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogsScreen(
    onBack: () -> Unit,
    vm: DebugLogsViewModel = hiltViewModel(),
) {
    val colors = learnColors()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope() // ФИКС: Scope для запуска фоновых задач из UI

    // ФИКС: используем lifecycle-aware collect с явным initialValue (пустой список)
    val allEntries by vm.entries.collectAsStateWithLifecycle(initialValue = emptyList())

    var activeLevels by remember {
        mutableStateOf(setOf(LogLevel.D, LogLevel.I, LogLevel.W, LogLevel.E))
    }
    var autoScroll by remember { mutableStateOf(true) }

    val filtered by remember(allEntries, activeLevels) {
        derivedStateOf {
            allEntries.filter { it.level in activeLevels }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem(filtered.size - 1)
            }
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Логи (${filtered.size}/${allEntries.size})",
                        fontSize = LearnTokens.FontSizeBodyLarge,
                        color = colors.textHi,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            "Автоскролл",
                            tint = if (autoScroll) colors.success else colors.textLow,
                        )
                    }
                    IconButton(onClick = {
                        // ФИКС: Запускаем suspend-функцию экспорта не блокируя UI
                        scope.launch {
                            val text = vm.export()
                            if (text.isNotEmpty()) {
                                copyToClipboard(context, text)
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Нет логов для копирования", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            "Копировать",
                            tint = colors.textHi,
                        )
                    }
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Filled.Delete, "Очистить", tint = colors.error)
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
                .padding(horizontal = LearnTokens.PaddingSm),
        ) {
            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LevelChip("D", LogLevel.D, colors.textLow, activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.D)
                }
                LevelChip("I", LogLevel.I, colors.accent, activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.I)
                }
                LevelChip("W", LogLevel.W, colors.warn, activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.W)
                }
                LevelChip("E", LogLevel.E, colors.error, activeLevels) {
                    activeLevels = toggleLevel(activeLevels, LogLevel.E)
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (allEntries.isEmpty()) "Логов пока нет"
                        else "Нет логов с выбранными уровнями",
                        color = colors.textLow,
                        fontSize = LearnTokens.FontSizeBody,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // ФИКС: используем itemsIndexed с уникальным ключом — index гарантирует
                    // отсутствие коллизий при одинаковых timestamp.
                    itemsIndexed(filtered, key = { idx, e -> "$idx-${e.timestamp}" }) { _, entry ->
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
    val colors = learnColors()
    val isSelected = level in active
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(if (isSelected) color.copy(alpha = 0.18f) else colors.surface)
            .border(
                LearnTokens.BorderThin,
                if (isSelected) color.copy(alpha = 0.5f) else colors.stroke,
                RoundedCornerShape(LearnTokens.RadiusXs),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = LearnTokens.FontSizeCaption,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) color else colors.textLow,
        )
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val colors = learnColors()
    val color = when (entry.level) {
        LogLevel.D -> colors.textLow
        LogLevel.I -> colors.accent
        LogLevel.W -> colors.warn
        LogLevel.E -> colors.error
    }
    val highlightBg = if (entry.level == LogLevel.E)
        colors.error.copy(alpha = 0.06f) else Color.Transparent

    Column(
        Modifier
            .fillMaxWidth()
            .background(highlightBg)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = runCatching { entry.formatted() }.getOrDefault("[error formatting log]"),
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
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("logs", text))
    }
}
