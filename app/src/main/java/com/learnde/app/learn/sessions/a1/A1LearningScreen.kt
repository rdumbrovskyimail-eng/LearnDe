// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА — v9 (Полный учебный экран + контур подсказок Flash Lite)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningScreen.kt
//
// Реализовано (вместо заглушек v7):
//   [1] Шапка: статус урока, подтверждение выхода, меню (Карта/Словарь/
//       История/Грамматика/Логи)
//   [2] Прогресс: чипы Слова/Освоено/Кластеры + бейдж «Повторить N»
//       + прогресс-бар текущего урока (отработанные леммы кластера)
//   [3] Карточка урока: леммы со статусами (услышано/произнесено/ошибка),
//       грамматический фокус, категория и сложность
//   [4] Степпер фаз: Разминка → Слова → Тренировка → Ситуация
//   [5] Чат: индикатор «ИИ говорит», пульс микрофона (как было) + бабблы
//       в цветах темы
//   [6] Плашка последней оценки с кнопкой «Оспорить» и автоскрытием
//   [7] GrammarSheet: автооткрытие при введении правила + чип повторного
//       открытия
//   [8] Статус-строка: соединение + CurrentFunctionBar (живые tool-call'ы)
//   [9] Контекстная нижняя панель: Старт/Повтор · Mute/Завершить ·
//       Переподключить
//  [10] Диалоги: финал урока (качество/фидбек), A1 завершён, ошибка,
//       подтверждение выхода; сборщик vm.effects (старт сессии, тосты,
//       системные тексты в Gemini)
//  [11] v9: TutorHintPanel — карточки второго контура (Gemini 3.1 Flash
//       Lite): правила, примеры, «обрати внимание». Живут между чатом и
//       оценкой, бейдж непрочитанного, раскрытие по тапу, при раскрытии
//       бейдж сбрасывается (vm.markHintsRead).
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.*
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.tutor.ui.TutorHintPanel
import com.learnde.app.presentation.learn.components.*
import com.learnde.app.presentation.learn.theme.*
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1LearningScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    onOpenCourseMap: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    vm: A1LearningViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()
    val hintCards by vm.hintCards.collectAsStateWithLifecycle()
    val hintUnread by vm.hintUnread.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = learnColors()

    var menuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var showGrammarSheet by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(
                LearnCoreIntent.Start(if (state.isReviewMode) "a1_review" else "a1_situation")
            )
        } else {
            Toast.makeText(context, "Без микрофона голосовой урок невозможен", Toast.LENGTH_LONG).show()
        }
    }

    fun startCoreSession(review: Boolean) {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            learnCoreViewModel.onIntent(
                LearnCoreIntent.Start(if (review) "a1_review" else "a1_situation")
            )
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ─── [10] Сборщик эффектов VM ───
    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                A1LearningEffect.RequestStartSession -> startCoreSession(review = false)
                A1LearningEffect.RequestStartReviewSession -> startCoreSession(review = true)
                A1LearningEffect.RequestStopSession ->
                    learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                is A1LearningEffect.SendSystemTextToGemini ->
                    learnCoreViewModel.sendSystemText(effect.text)
                is A1LearningEffect.ShowToast ->
                    Toast.makeText(context, effect.msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── [7] Автооткрытие грамматики ───
    LaunchedEffect(state.grammarIntroducedInSession) {
        if (state.grammarIntroducedInSession != null) showGrammarSheet = true
    }

    // ─── [1] Выход с подтверждением при активном уроке ───
    val requestExit: () -> Unit = {
        if (state.sessionActive) showExitConfirm = true else onBack()
    }
    BackHandler(onBack = requestExit)

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                state.isReviewMode -> "Повторение"
                                else -> "Обучение A1"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.sessionActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LiveDot(colors.success)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Урок идёт",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMid,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, "Меню")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Карта курса") },
                            leadingIcon = { Icon(Icons.Filled.Map, null) },
                            onClick = { menuExpanded = false; onOpenCourseMap() })
                        DropdownMenuItem(
                            text = { Text("Словарь") },
                            leadingIcon = { Icon(Icons.Filled.Translate, null) },
                            onClick = { menuExpanded = false; onOpenVocabulary() })
                        DropdownMenuItem(
                            text = { Text("История уроков") },
                            leadingIcon = { Icon(Icons.Filled.History, null) },
                            onClick = { menuExpanded = false; onOpenHistory() })
                        DropdownMenuItem(
                            text = { Text("Логи отладки") },
                            leadingIcon = { Icon(Icons.Filled.BugReport, null) },
                            onClick = { menuExpanded = false; onOpenDebugLogs() })
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
                .padding(horizontal = LearnTokens.PaddingLg)
        ) {
            // ─── [10] Загрузка данных / подготовка сессии ───
            AnimatedVisibility(state.loading || learnState.isPreparingSession) {
                InlineLoadingBar(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = LearnTokens.PaddingSm)
                )
            }

            // ─── [10] Ошибка ───
            state.error?.let { err ->
                ErrorCard(
                    text = err,
                    colors = colors,
                    onRetry = { vm.onIntent(A1LearningIntent.Refresh) },
                )
            }

            // ─── [2] Прогресс ───
            ProgressRow(state, colors)

            // ─── [3] Карточка урока ───
            if (!state.isReviewMode) {
                state.currentCluster?.let { cluster ->
                    ClusterCard(
                        cluster = cluster,
                        state = state,
                        colors = colors,
                        expanded = detailsExpanded,
                        onToggle = { detailsExpanded = !detailsExpanded },
                        onOpenGrammar = { showGrammarSheet = true },
                    )
                }
            }

            // ─── [4] Степпер фаз ───
            if (state.sessionActive) {
                PhaseStepper(state.currentPhase, colors)
            }

            // ─── [5] Чат ───
            ChatSection(
                transcript = learnState.transcript,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
                colors = colors,
                modifier = Modifier.weight(1f),
            )

            // ─── [11] Карточки-подсказки (3.1 Flash Lite) ───
            TutorHintPanel(
                cards = hintCards,
                unreadCount = hintUnread,
                enabled = state.sessionActive && hintCards.isNotEmpty(),
                onExpandedChanged = { expanded ->
                    if (expanded) vm.markHintsRead()
                },
            )

            // ─── [6] Плашка оценки ───
            EvaluationPlate(
                evaluation = state.lastEvaluation,
                colors = colors,
                onDispute = { vm.onIntent(A1LearningIntent.DisputeEvaluation(it)) },
            )

            // ─── [8] Статус-строка ───
            StatusRow(
                connection = learnState.connectionStatus,
                fnStatus = fnStatus,
                colors = colors,
            )

            // ─── [9] Панель действий ───
            ActionPanel(
                state = state,
                connection = learnState.connectionStatus,
                isMicActive = learnState.isMicActive,
                colors = colors,
                onStart = { vm.onIntent(A1LearningIntent.StartNextCluster) },
                onReview = { vm.onIntent(A1LearningIntent.StartReviewSession) },
                onStop = { vm.onIntent(A1LearningIntent.StopSession) },
                onToggleMic = { learnCoreViewModel.onIntent(LearnCoreIntent.ToggleMic) },
                onReconnect = { startCoreSession(review = state.isReviewMode) },
            )
        }
    }

    // ─── [7] Шторка грамматики ───
    if (showGrammarSheet) {
        com.learnde.app.learn.sessions.a1.grammar.GrammarSheet(
            onDismiss = { showGrammarSheet = false }
        )
    }

    // ─── [10] Финал урока ───
    if (state.sessionFinished) {
        AlertDialog(
            onDismissRequest = { vm.onIntent(A1LearningIntent.DismissFinalDialog) },
            icon = { Icon(Icons.Filled.EmojiEvents, null, tint = colors.accent) },
            title = { Text("Урок завершён") },
            text = {
                Column {
                    state.finalQuality?.let { q ->
                        Text("Качество: $q/7", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(state.finalFeedback ?: "Отличная работа — так держать!")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Произнесено слов: ${state.lemmasProducedThisSession.size} · " +
                            "услышано: ${state.lemmasHeardThisSession.size} · " +
                            "с ошибками: ${state.lemmasFailedThisSession.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMid,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.onIntent(A1LearningIntent.DismissFinalDialog)
                    vm.onIntent(A1LearningIntent.StartNextCluster)
                }) { Text("Следующий урок") }
            },
            dismissButton = {
                TextButton(onClick = { vm.onIntent(A1LearningIntent.DismissFinalDialog) }) {
                    Text("Готово")
                }
            },
        )
    }

    // ─── [10] A1 пройден целиком ───
    if (state.isA1Completed) {
        AlertDialog(
            onDismissRequest = { vm.onIntent(A1LearningIntent.AcknowledgeA1Completed) },
            icon = { Icon(Icons.Filled.WorkspacePremium, null, tint = colors.warning) },
            title = { Text("Уровень A1 пройден! 🎉") },
            text = { Text("Все ${state.totalClusters} уроков освоены. Можно повторять слабые слова или двигаться дальше.") },
            confirmButton = {
                TextButton(onClick = { vm.onIntent(A1LearningIntent.AcknowledgeA1Completed) }) {
                    Text("Ура!")
                }
            },
        )
    }

    // ─── [1] Подтверждение выхода ───
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Прервать урок?") },
            text = { Text("Прогресс по словам сохранится, но урок завершится без итоговой оценки.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    vm.onIntent(A1LearningIntent.StopSession)
                    onBack()
                }) { Text("Выйти", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Остаться") }
            },
        )
    }
}

// ════════════════════════════ КОМПОНЕНТЫ ════════════════════════════

@Composable
private fun LiveDot(color: Color) {
    val infinite = rememberInfiniteTransition(label = "live")
    val alpha by infinite.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a",
    )
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Composable
private fun ErrorCard(text: String, colors: LearnColors, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(LearnTokens.RadiusSm),
        color = colors.dangerSoft,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingSm),
    ) {
        Row(
            Modifier.padding(LearnTokens.PaddingMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ErrorOutline, null, tint = colors.danger)
            Spacer(Modifier.width(8.dp))
            Text(text, color = colors.danger, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

// ─── [2] Прогресс ───
@Composable
private fun ProgressRow(state: A1LearningState, colors: LearnColors) {
    Column(Modifier.padding(vertical = LearnTokens.PaddingSm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatChip("Слова", "${state.lemmasSeen}/${state.totalLemmas}", colors)
            StatChip("Освоено", "${state.lemmasMastered}", colors)
            StatChip("Кластеры", "${state.clustersMastered}/${state.totalClusters}", colors)
            if (state.weakLemmasCount > 0) {
                Surface(
                    shape = RoundedCornerShape(LearnTokens.RadiusXs),
                    color = colors.warnSoft,
                ) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${state.weakLemmasCount}",
                            fontWeight = FontWeight.SemiBold,
                            color = colors.warning,
                        )
                        Text(
                            "повторить",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.warning,
                        )
                    }
                }
            }
        }

        // Прогресс текущего урока: сколько лемм кластера уже отработано
        val clusterLemmas = state.currentCluster?.let { parseLemmas(it.lemmasJson) }.orEmpty()
        if (state.sessionActive && clusterLemmas.isNotEmpty()) {
            val touched = clusterLemmas.count {
                it in state.lemmasProducedThisSession || it in state.lemmasHeardThisSession
            }
            Spacer(Modifier.height(LearnTokens.PaddingSm))
            LinearProgressIndicator(
                progress = { touched / clusterLemmas.size.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.accent,
                trackColor = colors.surfaceVar,
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, colors: LearnColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, color = colors.textHi)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textMid)
    }
}

// ─── [3] Карточка урока ───
@Composable
private fun ClusterCard(
    cluster: ClusterA1Entity,
    state: A1LearningState,
    colors: LearnColors,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenGrammar: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(LearnTokens.RadiusMd),
        color = colors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingXs)
            .clickable { onToggle() },
    ) {
        Column(Modifier.padding(LearnTokens.PaddingMd)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cluster.titleRu, fontWeight = FontWeight.SemiBold, color = colors.textHi)
                    Text(
                        cluster.titleDe,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DifficultyDots(cluster.difficulty, colors)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, tint = colors.textLow,
                )
            }

            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(LearnTokens.PaddingSm))

                    // Леммы кластера со статусами текущей сессии
                    val lemmas = remember(cluster.id) { parseLemmas(cluster.lemmasJson) }
                    FlowRowLemmas(lemmas, state, colors)

                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${cluster.category} · фокус: ${cluster.grammarFocus}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMid,
                            modifier = Modifier.weight(1f),
                        )
                        if (cluster.grammarRuleId != null ||
                            state.grammarIntroducedInSession != null
                        ) {
                            AssistChip(
                                onClick = onOpenGrammar,
                                label = { Text("Правило") },
                                leadingIcon = { Icon(Icons.Filled.MenuBook, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DifficultyDots(difficulty: Int, colors: LearnColors) {
    Row(Modifier.padding(horizontal = 8.dp)) {
        repeat(4) { i ->
            Box(
                Modifier
                    .padding(1.5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i < difficulty) colors.accent else colors.surfaceVar)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowLemmas(lemmas: List<String>, state: A1LearningState, colors: LearnColors) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lemmas.forEach { lemma ->
            val (bg, fg) = when {
                lemma in state.lemmasFailedThisSession -> colors.dangerSoft to colors.danger
                lemma in state.lemmasProducedThisSession -> colors.successSoft to colors.success
                lemma in state.lemmasHeardThisSession -> colors.accentSoft to colors.accent
                else -> colors.surfaceVar to colors.textMid
            }
            Surface(shape = RoundedCornerShape(LearnTokens.RadiusXs), color = bg) {
                Text(
                    lemma,
                    color = fg,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─── [4] Степпер фаз ───
@Composable
private fun PhaseStepper(phase: A1Phase, colors: LearnColors) {
    data class Step(val label: String, val phases: Set<A1Phase>)

    val steps = listOf(
        Step("Разминка", setOf(A1Phase.WARM_UP)),
        Step("Слова", setOf(A1Phase.INTRODUCE)),
        Step("Тренировка", setOf(A1Phase.DRILL, A1Phase.GRAMMAR)),
        Step("Ситуация", setOf(A1Phase.APPLY, A1Phase.COOL_DOWN, A1Phase.FINISHED)),
    )
    val activeIndex = steps.indexOfFirst { phase in it.phases }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, step ->
            val passed = activeIndex >= 0 && i < activeIndex
            val active = i == activeIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> colors.accent
                                passed -> colors.success
                                else -> colors.surfaceVar
                            }
                        )
                )
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colors.textHi else colors.textLow,
                )
            }
            if (i < steps.lastIndex) {
                Box(
                    Modifier
                        .weight(0.4f)
                        .height(2.dp)
                        .background(if (passed) colors.success else colors.surfaceVar)
                )
            }
        }
    }
}

// ─── [5] Чат ───
@Composable
private fun ChatSection(
    transcript: List<ConversationMessage>,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    colors: LearnColors,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val pinned by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= transcript.lastIndex - 1
        }
    }
    LaunchedEffect(transcript.size, isAiSpeaking) {
        if (pinned && transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.lastIndex)
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Диалог", style = MaterialTheme.typography.labelSmall, color = colors.textLow)
            Spacer(Modifier.weight(1f))
            if (isAiSpeaking) SpeakingDots(colors.accent)
            Spacer(Modifier.width(8.dp))
            MicPulse(isMicActive, colors.danger)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = LearnTokens.PaddingSm),
        ) {
            items(transcript) { msg -> ChatBubble(msg, colors) }
        }
    }
}

@Composable
private fun SpeakingDots(color: Color) {
    val infinite = rememberInfiniteTransition(label = "spk")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val scale by infinite.animateFloat(
                0.5f, 1f,
                infiniteRepeatable(
                    tween(400, delayMillis = i * 130),
                    RepeatMode.Reverse,
                ),
                label = "d$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun MicPulse(active: Boolean, color: Color) {
    if (!active) return
    val infinite = rememberInfiniteTransition(label = "mic")
    val scale by infinite.animateFloat(
        0.8f, 1.2f,
        infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "s",
    )
    Icon(Icons.Filled.Mic, null, tint = color, modifier = Modifier.scale(scale).size(18.dp))
}

@Composable
private fun ChatBubble(msg: ConversationMessage, colors: LearnColors) {
    val isUser = msg.role == ConversationMessage.ROLE_USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) colors.bubbleUser else colors.bubbleTutor,
            shape = RoundedCornerShape(
                topStart = LearnTokens.RadiusSm,
                topEnd = LearnTokens.RadiusSm,
                bottomStart = if (isUser) LearnTokens.RadiusSm else LearnTokens.RadiusXxs,
                bottomEnd = if (isUser) LearnTokens.RadiusXxs else LearnTokens.RadiusSm,
            ),
            modifier = Modifier
                .padding(vertical = 3.dp)
                .widthIn(max = 300.dp),
        ) {
            Text(
                msg.text,
                color = colors.textHi,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// ─── [6] Оценка ───
@Composable
private fun EvaluationPlate(
    evaluation: LastEvaluation?,
    colors: LearnColors,
    onDispute: (String) -> Unit,
) {
    var visible by remember(evaluation) { mutableStateOf(evaluation != null) }
    LaunchedEffect(evaluation) {
        if (evaluation != null) {
            visible = true
            delay(7_000)
            visible = false
        }
    }
    AnimatedVisibility(visible && evaluation != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val ev = evaluation ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(LearnTokens.RadiusSm),
            color = if (ev.wasCorrect) colors.successSoft else colors.dangerSoft,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LearnTokens.PaddingXs),
        ) {
            Row(
                Modifier.padding(LearnTokens.PaddingMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (ev.wasCorrect) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    null,
                    tint = if (ev.wasCorrect) colors.success else colors.danger,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ev.lemma} — ${ev.quality}/7",
                        fontWeight = FontWeight.SemiBold,
                        color = if (ev.wasCorrect) colors.success else colors.danger,
                    )
                    Text(
                        ev.feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMid,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!ev.wasCorrect) {
                    TextButton(onClick = { onDispute(ev.lemma) }) { Text("Оспорить") }
                }
            }
        }
    }
}

// ─── [8] Статус-строка ───
@Composable
private fun StatusRow(
    connection: LearnConnectionStatus,
    fnStatus: FunctionStatus,
    colors: LearnColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (label, color) = when (connection) {
            LearnConnectionStatus.Disconnected -> "Не подключено" to colors.textLow
            LearnConnectionStatus.Connecting -> "Подключение…" to colors.warning
            LearnConnectionStatus.Negotiating -> "Настройка…" to colors.warning
            LearnConnectionStatus.Ready -> "Готов" to colors.success
            LearnConnectionStatus.Recording -> "Слушаю" to colors.accent
            LearnConnectionStatus.Reconnecting -> "Переподключение…" to colors.warning
        }
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.width(LearnTokens.PaddingMd))
        CurrentFunctionBar(status = fnStatus, modifier = Modifier.weight(1f))
    }
}

// ─── [9] Панель действий ───
@Composable
private fun ActionPanel(
    state: A1LearningState,
    connection: LearnConnectionStatus,
    isMicActive: Boolean,
    colors: LearnColors,
    onStart: () -> Unit,
    onReview: () -> Unit,
    onStop: () -> Unit,
    onToggleMic: () -> Unit,
    onReconnect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingMd),
        horizontalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm),
    ) {
        when {
            // Урок числится активным, но связь упала → восстановление
            state.sessionActive && connection == LearnConnectionStatus.Disconnected -> {
                Button(
                    onClick = onReconnect,
                    modifier = Modifier
                        .weight(1f)
                        .height(LearnTokens.ButtonHeightLg),
                    shape = RoundedCornerShape(LearnTokens.RadiusXl),
                ) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Продолжить урок")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.height(LearnTokens.ButtonHeightLg),
                    shape = RoundedCornerShape(LearnTokens.RadiusXl),
                ) { Text("Завершить") }
            }

            state.sessionActive -> {
                OutlinedButton(
                    onClick = onToggleMic,
                    modifier = Modifier.height(LearnTokens.ButtonHeightLg),
                    shape = RoundedCornerShape(LearnTokens.RadiusXl),
                ) {
                    Icon(
                        if (isMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = "Микрофон",
                        tint = if (isMicActive) colors.accent else colors.textLow,
                    )
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(LearnTokens.ButtonHeightLg),
                    shape = RoundedCornerShape(LearnTokens.RadiusXl),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.dangerSoft,
                        contentColor = colors.danger,
                    ),
                ) { Text("Завершить урок") }
            }

            else -> {
                Button(
                    onClick = onStart,
                    enabled = !state.loading,
                    modifier = Modifier
                        .weight(1f)
                        .height(LearnTokens.ButtonHeightLg),
                    shape = RoundedCornerShape(LearnTokens.RadiusXl),
                ) { Text("Начать урок") }
                if (state.weakLemmasCount > 0) {
                    OutlinedButton(
                        onClick = onReview,
                        modifier = Modifier.height(LearnTokens.ButtonHeightLg),
                        shape = RoundedCornerShape(LearnTokens.RadiusXl),
                    ) { Text("Повторить (${state.weakLemmasCount})") }
                }
            }
        }
    }
}

// ════════════════════════════ УТИЛИТЫ ════════════════════════════

private val lemmasJsonParser = Json { ignoreUnknownKeys = true }

private fun parseLemmas(lemmasJson: String): List<String> =
    runCatching { lemmasJsonParser.decodeFromString<List<String>>(lemmasJson) }
        .getOrElse { emptyList() }
