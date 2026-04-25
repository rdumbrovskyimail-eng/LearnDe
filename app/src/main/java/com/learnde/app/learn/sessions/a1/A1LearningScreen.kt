// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningScreen.kt
//
// КЛЮЧЕВЫЕ ИЗМЕНЕНИЯ v5.0:
//   1. Убран fullscreen SessionLoadingOverlay → заменён на InlineLoadingBar
//      под TopAppBar (без затемнения экрана). Исчезает при первом аудио.
//   2. Добавлен AudioParticleBox справа от inline-loader.
//   3. Кольца прогресса (Леммы/Уроки/Правила) уменьшены с 94dp → 56dp
//      и сжаты в одну компактную строку.
//   4. CurrentClusterCard минимизирован (без огромного описания, доступно
//      по tap'у "детали урока").
//   5. Чат стал главным элементом экрана — занимает ~60% высоты.
//   6. Single-source theme (LearnColors).
//   7. Pluralization: "У вас 1 слово" / "5 слов".
//   8. TopAppBar: одно компактное действие (меню) вместо 4 иконок.
//   9. Убраны все эмодзи как UI; иконки и weight-контраст вместо цветов.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.AudioParticleBox
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import com.learnde.app.presentation.learn.components.InlineLoadingBar
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.Plural
import com.learnde.app.presentation.learn.theme.learnColors

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
    val context = LocalContext.current
    val colors = learnColors()

    val activity = context as? android.app.Activity
    var showRationaleDialog by remember { mutableStateOf(false) }
    var rationaleIsPermanent by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var showGrammarSheet by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val sessionId = if (state.isReviewMode) "a1_review" else "a1_situation"
            learnCoreViewModel.onIntent(LearnCoreIntent.Start(sessionId))
        } else {
            val shouldShow = activity?.let {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    it, android.Manifest.permission.RECORD_AUDIO,
                )
            } ?: false
            rationaleIsPermanent = !shouldShow
            showRationaleDialog = true
        }
    }

    if (showRationaleDialog) {
        com.learnde.app.presentation.learn.components.MicPermissionRationaleDialog(
            showSettingsButton = rationaleIsPermanent,
            onDismiss = { showRationaleDialog = false },
            onRequestAgain = {
                showRationaleDialog = false
                if (rationaleIsPermanent) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                } else {
                    micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            },
            context = context,
        )
    }

    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                is A1LearningEffect.RequestStartSession -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        learnCoreViewModel.onIntent(LearnCoreIntent.Start("a1_situation"))
                    } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                is A1LearningEffect.RequestStartReviewSession -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        learnCoreViewModel.onIntent(LearnCoreIntent.Start("a1_review"))
                    } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                is A1LearningEffect.RequestStopSession ->
                    learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                is A1LearningEffect.ShowToast ->
                    Toast.makeText(context, effect.msg, Toast.LENGTH_SHORT).show()
                is A1LearningEffect.SendSystemTextToGemini ->
                    learnCoreViewModel.sendSystemText(effect.text)
            }
        }
    }

    val exitAndBack: () -> Unit = {
        if (state.sessionActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.isReviewMode) Icons.Filled.Refresh else Icons.Filled.School,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(Modifier.width(LearnTokens.PaddingSm))
                        Text(
                            if (state.isReviewMode) "Повторение" else "Обучение A1",
                            fontSize = LearnTokens.FontSizeTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textHi,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = exitAndBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Назад",
                            tint = colors.textHi,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                "Меню",
                                tint = colors.textMid,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Карта курса") },
                                onClick = { menuExpanded = false; onOpenCourseMap() },
                                leadingIcon = { Icon(Icons.Filled.Map, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Словарь") },
                                onClick = { menuExpanded = false; onOpenVocabulary() },
                                leadingIcon = { Icon(Icons.Filled.MenuBook, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("История уроков") },
                                onClick = { menuExpanded = false; onOpenHistory() },
                                leadingIcon = { Icon(Icons.Filled.History, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Грамматика") },
                                onClick = { menuExpanded = false; showGrammarSheet = true },
                                leadingIcon = { Icon(Icons.Filled.MenuBook, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Логи") },
                                onClick = { menuExpanded = false; onOpenDebugLogs() },
                                leadingIcon = { Icon(Icons.Filled.BugReport, null) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
            )
        },
        bottomBar = {
            CurrentFunctionBar(
                status = fnStatus,
                modifier = Modifier.padding(
                    horizontal = LearnTokens.PaddingMd,
                    vertical = LearnTokens.PaddingSm,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingLg),
        ) {
            // ─── Inline loader + AudioParticleBox ───
            AnimatedVisibility(
                visible = learnState.isPreparingSession && learnState.transcript.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LearnTokens.PaddingSm, bottom = LearnTokens.PaddingMd),
                ) {
                    InlineLoadingBar(modifier = Modifier.weight(1f))
                }
            }

            AnimatedVisibility(visible = state.sessionActive) {
                Box(Modifier.fillMaxWidth().padding(vertical = LearnTokens.PaddingSm), contentAlignment = Alignment.Center) {
                    AudioParticleBox(
                        playbackSync = learnCoreViewModel.audioPlaybackFlow,
                        size = 36.dp,
                    )
                }
            }

            if (state.loading) {
                LoadingSection()
                return@Column
            }
            if (state.error != null) {
                ErrorSection(state.error ?: "Неизвестная ошибка")
                return@Column
            }

            // ─── КОМПАКТНЫЕ кольца прогресса (56dp) ───
            CompactProgressRow(state)
            Spacer(Modifier.height(LearnTokens.PaddingMd))

            // ─── Минимизированная карточка текущего урока ───
            if (!state.isReviewMode) {
                state.currentCluster?.let { cluster ->
                    CompactClusterCard(
                        cluster = cluster,
                        sessionActive = state.sessionActive,
                        expanded = detailsExpanded,
                        onToggleExpanded = { detailsExpanded = !detailsExpanded },
                    )
                } ?: AllClustersDoneCard()
            } else {
                CompactReviewCard(weakCount = state.weakLemmasCount)
            }

            Spacer(Modifier.height(LearnTokens.PaddingMd))

            // ─── Phase Timeline (только для !review) ───
            AnimatedVisibility(
                visible = state.sessionActive && !state.isReviewMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    PhaseTimeline(current = state.currentPhase)
                    Spacer(Modifier.height(LearnTokens.PaddingMd))
                }
            }

            // ─── Карточка последней оценки леммы ───
            AnimatedVisibility(
                visible = state.lastEvaluation != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.lastEvaluation?.let { ev ->
                    LemmaEvaluationCard(ev) {
                        vm.onIntent(A1LearningIntent.DisputeEvaluation(ev.lemma))
                    }
                }
            }



            // ─── SessionLiveStats только во время сессии ───
            AnimatedVisibility(
                visible = state.sessionActive,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                    SessionLiveStats(
                        heard = state.lemmasHeardThisSession.size,
                        produced = state.lemmasProducedThisSession.size,
                        failed = state.lemmasFailedThisSession.size,
                        grammarIntroduced = state.grammarIntroducedInSession,
                    )
                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                }
            }

            // ─── ЧАТ ─── (главный элемент, weight=1f)
            if (learnState.error != null) {
                ErrorSection(learnState.error ?: "Ошибка связи")
                Spacer(Modifier.height(LearnTokens.PaddingSm))
            }
            if (state.sessionActive && learnState.transcript.isNotEmpty()) {
                ChatSection(
                    transcript = learnState.transcript,
                    isAiSpeaking = learnState.isAiSpeaking,
                    isMicActive = learnState.isMicActive,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(LearnTokens.PaddingMd))
            } else if (state.sessionActive) {
                EmptyChatPlaceholder(
                    isMicActive = learnState.isMicActive,
                    scenario = state.currentCluster?.scenarioHint,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(LearnTokens.PaddingMd))
            } else {
                Spacer(Modifier.weight(1f))
            }

            // ─── Кнопка действий ─── (всегда внизу)
            BottomActionButton(
                state = state,
                vm = vm,
                conn = learnState.connectionStatus,
                learnCoreViewModel = learnCoreViewModel,
                isAiSpeaking = learnState.isAiSpeaking,
                onShowToast = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
            )
            Spacer(Modifier.height(LearnTokens.PaddingSm))
        }
    }

    if (showGrammarSheet) {
        com.learnde.app.learn.sessions.a1.grammar.GrammarSheet(
            onDismiss = { showGrammarSheet = false },
        )
    }

    if (state.sessionFinished) {
        SessionFinishedDialog(
            quality = state.finalQuality ?: 5,
            feedback = state.finalFeedback ?: "",
            lemmasProduced = state.lemmasProducedThisSession.size,
            lemmasFailed = state.lemmasFailedThisSession.size,
            isReviewMode = state.isReviewMode,
            onContinue = { vm.onIntent(A1LearningIntent.DismissFinalDialog) },
        )
    }

    if (state.isA1Completed) {
        A1CompletedDialog(onClose = { vm.onIntent(A1LearningIntent.DismissFinalDialog) })
    }
}

// ═══════════════════════════════════════════════════════════
// LOADING / ERROR
// ═══════════════════════════════════════════════════════════
@Composable
private fun LoadingSection() {
    val colors = learnColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(LearnTokens.PaddingMd))
            Text(
                "Загружаем прогресс…",
                fontSize = LearnTokens.FontSizeBody,
                color = colors.textMid,
            )
        }
    }
}

@Composable
private fun ErrorSection(msg: String) {
    val colors = learnColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Cancel,
                null,
                tint = colors.error,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(LearnTokens.PaddingSm))
            Text("Ошибка: $msg", color = colors.error, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ═══════════════════════════════════════════════════════════
// КОМПАКТНАЯ строка прогресса — 56dp кольца
// ═══════════════════════════════════════════════════════════
@Composable
private fun CompactProgressRow(state: A1LearningState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactProgressItem(
            label = "Леммы",
            current = state.lemmasMastered,
            total = state.totalLemmas,
        )
        CompactProgressItem(
            label = "Уроки",
            current = state.clustersMastered,
            total = state.totalClusters,
        )
        CompactProgressItem(
            label = "Правила",
            current = state.grammarIntroduced,
            total = state.grammarTotal,
        )
    }
}

@Composable
private fun CompactProgressItem(label: String, current: Int, total: Int) {
    val colors = learnColors()
    val fraction by animateFloatAsState(
        targetValue = if (total == 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "p",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val stroke = with(density) { 4.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                drawArc(
                    color = colors.stroke.copy(alpha = 0.5f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = colors.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text(
                "$current",
                fontSize = LearnTokens.FontSizeBodyLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textHi,
            )
        }
        Spacer(Modifier.width(LearnTokens.PaddingSm))
        Column {
            Text(
                label,
                fontSize = LearnTokens.FontSizeCaption,
                fontWeight = FontWeight.SemiBold,
                color = colors.textHi,
            )
            Text(
                "из $total",
                fontSize = LearnTokens.FontSizeMicro,
                color = colors.textLow,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// CompactClusterCard — мини-карточка с раскрытием
// ═══════════════════════════════════════════════════════════
@Composable
private fun CompactClusterCard(
    cluster: com.learnde.app.learn.data.db.ClusterA1Entity,
    sessionActive: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = learnColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.surface)
            .border(
                LearnTokens.BorderThin,
                if (sessionActive) colors.accent.copy(alpha = 0.4f) else colors.stroke,
                RoundedCornerShape(LearnTokens.RadiusMd),
            )
            .clickable { onToggleExpanded() }
            .padding(LearnTokens.PaddingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ТЕКУЩИЙ УРОК",
                    fontSize = LearnTokens.FontSizeMicro,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                    letterSpacing = LearnTokens.CapsLetterSpacing,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    cluster.titleRu,
                    fontSize = LearnTokens.FontSizeBodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textHi,
                )
                Text(
                    cluster.titleDe,
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textMid,
                    fontWeight = FontWeight.Medium,
                )
            }
            DifficultyDots(cluster.difficulty)
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                Spacer(Modifier.height(LearnTokens.PaddingSm))
                Text(
                    text = cluster.scenarioHint,
                    modifier = Modifier
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = colors.textMid,
                )
                if (cluster.grammarFocus.isNotBlank()) {
                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(LearnTokens.RadiusXs))
                                .background(colors.accentSoft)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "ГРАММАТИКА",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                                letterSpacing = LearnTokens.CapsLetterSpacing,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            cluster.grammarFocus,
                            fontSize = LearnTokens.FontSizeCaption,
                            color = colors.textHi,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DifficultyDots(difficulty: Int) {
    val colors = learnColors()
    Row {
        for (i in 1..4) {
            val filled = i <= difficulty
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (filled) colors.accent else colors.stroke),
            )
        }
    }
}

@Composable
private fun AllClustersDoneCard() {
    val colors = learnColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.successSoft)
            .border(
                LearnTokens.BorderThin,
                colors.success.copy(alpha = 0.3f),
                RoundedCornerShape(LearnTokens.RadiusMd),
            )
            .padding(LearnTokens.PaddingMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            null,
            tint = colors.success,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Все уроки A1 пройдены",
            fontSize = LearnTokens.FontSizeBodyLarge,
            fontWeight = FontWeight.Bold,
            color = colors.success,
        )
    }
}

@Composable
private fun CompactReviewCard(weakCount: Int) {
    val colors = learnColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusMd))
            .padding(LearnTokens.PaddingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Refresh, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(LearnTokens.PaddingMd))
        Column(Modifier.weight(1f)) {
            Text(
                "Быстрое повторение",
                fontSize = LearnTokens.FontSizeBodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textHi,
            )
            Text(
                "$weakCount ${Plural.word(weakCount)} · 5–7 минут",
                fontSize = LearnTokens.FontSizeCaption,
                color = colors.textMid,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// PHASE TIMELINE — без подписей (компактно)
// ═══════════════════════════════════════════════════════════
@Composable
private fun PhaseTimeline(current: A1Phase) {
    val colors = learnColors()
    val phases = listOf(
        A1Phase.IDLE, A1Phase.WARM_UP, A1Phase.INTRODUCE, A1Phase.DRILL,
        A1Phase.APPLY, A1Phase.GRAMMAR, A1Phase.COOL_DOWN, A1Phase.FINISHED,
    )
    val labels = listOf("", "Разминка", "Новое", "Тренаж", "Применяй", "Правило", "Итог", "Готово")
    val currentIndex = phases.indexOfFirst { it == current }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            phases.forEachIndexed { index, _ ->
                val isDone = currentIndex > index
                val isActive = currentIndex == index
                val color = when {
                    isActive -> colors.accent
                    isDone -> colors.accent.copy(alpha = 0.4f)
                    else -> colors.stroke
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (isActive) 4.dp else 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            labels[currentIndex.coerceIn(0, labels.lastIndex)],
            fontSize = LearnTokens.FontSizeMicro,
            fontWeight = FontWeight.Medium,
            color = colors.accent,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// LEMMA EVALUATION CARD — компактная
// ═══════════════════════════════════════════════════════════
@Composable
private fun LemmaEvaluationCard(ev: LastEvaluation, onDispute: () -> Unit) {
    val colors = learnColors()
    val accent = when {
        ev.quality >= 4 -> colors.success
        ev.quality >= 2 -> colors.warn
        else -> colors.error
    }
    val accentSoft = when {
        ev.quality >= 4 -> colors.successSoft
        ev.quality >= 2 -> colors.warnSoft
        else -> colors.errorSoft
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(accentSoft)
            .border(LearnTokens.BorderThin, accent.copy(alpha = 0.3f), RoundedCornerShape(LearnTokens.RadiusSm))
            .padding(LearnTokens.PaddingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${ev.quality}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = LearnTokens.FontSizeBody,
                )
            }
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            Column(Modifier.weight(1f)) {
                Text(
                    ev.lemma,
                    fontSize = LearnTokens.FontSizeBody,
                    fontWeight = FontWeight.Bold,
                    color = colors.textHi,
                )
                if (ev.feedback.isNotBlank()) {
                    Text(
                        ev.feedback,
                        fontSize = LearnTokens.FontSizeCaption,
                        color = colors.textMid,
                        lineHeight = 15.sp,
                    )
                }
            }
            if (ev.wasCorrect) {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = colors.success,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (ev.diagnosis.isError) {
            Spacer(Modifier.height(LearnTokens.PaddingSm))
            DiagnosisChips(ev)
            if (ev.diagnosis.specifics.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    ev.diagnosis.specifics,
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textMid,
                    lineHeight = 15.sp,
                )
            }
        }
        if (!ev.wasCorrect) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDispute,
                modifier = Modifier
                    .align(Alignment.End)
                    .height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    "Я сказал правильно",
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DiagnosisChips(ev: LastEvaluation) {
    val sourceLabel = when (ev.diagnosis.source) {
        com.learnde.app.learn.domain.ErrorSource.L1_TRANSFER -> "влияние русского"
        com.learnde.app.learn.domain.ErrorSource.OVERGENERALIZATION -> "широкое правило"
        com.learnde.app.learn.domain.ErrorSource.SIMPLIFICATION -> "упрощение"
        com.learnde.app.learn.domain.ErrorSource.COMMUNICATION_STRATEGY -> "обход"
        com.learnde.app.learn.domain.ErrorSource.NONE -> null
    }
    val depthLabel = when (ev.diagnosis.depth) {
        com.learnde.app.learn.domain.ErrorDepth.SLIP -> "оговорка"
        com.learnde.app.learn.domain.ErrorDepth.MISTAKE -> "неуверенность"
        com.learnde.app.learn.domain.ErrorDepth.ERROR -> "не знал"
        com.learnde.app.learn.domain.ErrorDepth.NONE -> null
    }
    val categoryLabel = when (ev.diagnosis.category) {
        com.learnde.app.learn.domain.ErrorCategory.GENDER -> "артикль"
        com.learnde.app.learn.domain.ErrorCategory.CASE -> "падеж"
        com.learnde.app.learn.domain.ErrorCategory.WORD_ORDER -> "порядок слов"
        com.learnde.app.learn.domain.ErrorCategory.LEXICAL -> "слово"
        com.learnde.app.learn.domain.ErrorCategory.PHONOLOGY -> "звук"
        com.learnde.app.learn.domain.ErrorCategory.PRAGMATICS -> "регистр"
        com.learnde.app.learn.domain.ErrorCategory.CONJUGATION -> "спряжение"
        com.learnde.app.learn.domain.ErrorCategory.NEGATION -> "отрицание"
        com.learnde.app.learn.domain.ErrorCategory.PLURAL -> "мн. число"
        com.learnde.app.learn.domain.ErrorCategory.PREPOSITION -> "предлог"
        com.learnde.app.learn.domain.ErrorCategory.NONE -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sourceLabel?.let { DiagChip(it) }
        depthLabel?.let { DiagChip(it) }
        categoryLabel?.let { DiagChip(it) }
    }
}

@Composable
private fun DiagChip(text: String) {
    val colors = learnColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceVar)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = colors.textMid,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// SESSION LIVE STATS — компактная горизонтальная строка
// ═══════════════════════════════════════════════════════════
@Composable
private fun SessionLiveStats(
    heard: Int,
    produced: Int,
    failed: Int,
    grammarIntroduced: String?,
) {
    val colors = learnColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm)) {
        StatChip("услышал", "$heard", colors.textMid, Modifier.weight(1f))
        StatChip("произнёс", "$produced", colors.success, Modifier.weight(1f))
        StatChip("ошибся", "$failed", colors.error, Modifier.weight(1f))
    }
    if (grammarIntroduced != null) {
        Spacer(Modifier.height(LearnTokens.PaddingSm))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LearnTokens.RadiusSm))
                .background(colors.accentSoft)
                .padding(LearnTokens.PaddingSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.MenuBook,
                null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Новое правило: $grammarIntroduced",
                fontSize = LearnTokens.FontSizeCaption,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    val colors = learnColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.surfaceVar)
            .padding(vertical = 6.dp),
    ) {
        Text(
            value,
            fontSize = LearnTokens.FontSizeBodyLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            label,
            fontSize = 10.sp,
            color = colors.textLow,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// CHAT SECTION — главный элемент экрана
// ═══════════════════════════════════════════════════════════
@Composable
private fun ChatSection(
    transcript: List<ConversationMessage>,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = learnColors()
    val listState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Диалог",
                fontSize = LearnTokens.FontSizeCaption,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMid,
            )
            Spacer(Modifier.weight(1f))
            SpeakingIndicator(isAiSpeaking = isAiSpeaking, isMicActive = isMicActive)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                .background(colors.surface)
                .border(
                    LearnTokens.BorderThin,
                    colors.stroke,
                    RoundedCornerShape(LearnTokens.RadiusMd),
                )
                .padding(LearnTokens.PaddingSm),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    transcript,
                    key = { msg -> "${msg.timestamp}_${msg.role}_${msg.text.length}" }
                ) { msg ->
                    ChatBubble(msg)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    val colors = learnColors()
    val isUser = message.role == ConversationMessage.ROLE_USER
    val text = message.text.trim()
    if (text.isEmpty()) return

    val bg = if (isUser) colors.accentSoft else colors.surfaceVar
    val border = if (isUser) colors.accent.copy(alpha = 0.2f) else colors.stroke
    val labelColor = if (isUser) colors.accent else colors.textMid

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .clip(
                    RoundedCornerShape(
                        topStart = LearnTokens.RadiusSm,
                        topEnd = LearnTokens.RadiusSm,
                        bottomStart = if (isUser) LearnTokens.RadiusSm else 4.dp,
                        bottomEnd = if (isUser) 4.dp else LearnTokens.RadiusSm,
                    ),
                )
                .background(bg)
                .border(
                    LearnTokens.BorderThin,
                    border,
                    RoundedCornerShape(
                        topStart = LearnTokens.RadiusSm,
                        topEnd = LearnTokens.RadiusSm,
                        bottomStart = if (isUser) LearnTokens.RadiusSm else 4.dp,
                        bottomEnd = if (isUser) 4.dp else LearnTokens.RadiusSm,
                    ),
                )
                .padding(horizontal = LearnTokens.PaddingMd, vertical = 8.dp),
        ) {
            Text(
                if (isUser) "ВЫ" else "GEMINI",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                letterSpacing = LearnTokens.CapsLetterSpacing,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text,
                fontSize = LearnTokens.FontSizeBody,
                color = colors.textHi,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SpeakingIndicator(isAiSpeaking: Boolean, isMicActive: Boolean) {
    val colors = learnColors()
    val (label, color, icon) = when {
        isAiSpeaking -> Triple("Gemini говорит", colors.accent, Icons.Filled.VolumeUp)
        isMicActive -> Triple("слушаю", colors.success, Icons.Filled.Mic)
        else -> Triple("пауза", colors.textLow, Icons.Filled.PauseCircleOutline)
    }
    val active = isAiSpeaking || isMicActive
    val pulse = rememberInfiniteTransition(label = "speakPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pa",
    )
    val effAlpha = if (active) pulseAlpha else 1f

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(color.copy(alpha = 0.10f * effAlpha))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color.copy(alpha = effAlpha), modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            label,
            fontSize = LearnTokens.FontSizeMicro,
            color = color.copy(alpha = effAlpha),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyChatPlaceholder(
    isMicActive: Boolean,
    scenario: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = learnColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusMd))
            .padding(LearnTokens.PaddingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Forum,
            null,
            tint = colors.textLow,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(LearnTokens.PaddingSm))
        Text(
            if (isMicActive) "Слушаю вас…" else "Gemini начнёт первым…",
            fontSize = LearnTokens.FontSizeBody,
            fontWeight = FontWeight.SemiBold,
            color = colors.textHi,
            textAlign = TextAlign.Center,
        )
        if (!scenario.isNullOrBlank()) {
            Spacer(Modifier.height(LearnTokens.PaddingSm))
            Text(
                scenario,
                fontSize = LearnTokens.FontSizeCaption,
                lineHeight = 16.sp,
                color = colors.textMid,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// BOTTOM ACTION BUTTON — без градиентов
// ═══════════════════════════════════════════════════════════
@Composable
private fun BottomActionButton(
    state: A1LearningState,
    vm: A1LearningViewModel,
    conn: LearnConnectionStatus,
    learnCoreViewModel: LearnCoreViewModel,
    isAiSpeaking: Boolean,
    onShowToast: (String) -> Unit,
) {
    val colors = learnColors()
    when {
        state.sessionActive -> {
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (isAiSpeaking) {
                            onShowToast("Подождите, ИИ ещё говорит…")
                        } else {
                            learnCoreViewModel.sendSystemText(
                                "[СИСТЕМА]: Ученик нажал кнопку 'Не знаю'. " +
                                    "Дай правильный ответ и краткое объяснение по-русски.",
                            )
                        }
                    },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(LearnTokens.RadiusSm),
                    contentPadding = PaddingValues(horizontal = LearnTokens.PaddingMd, vertical = 6.dp),
                    border = BorderStroke(LearnTokens.BorderThin, colors.stroke),
                ) {
                    Text(
                        "Не знаю",
                        fontSize = LearnTokens.FontSizeCaption,
                        fontWeight = FontWeight.Medium,
                        color = colors.textMid,
                    )
                }
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                Button(
                    onClick = { vm.onIntent(A1LearningIntent.StopSession) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(LearnTokens.RadiusSm),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                ) {
                    Icon(Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.isReviewMode) "Остановить" else "Стоп",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = LearnTokens.FontSizeBody,
                    )
                }
            }
        }
        state.currentCluster != null -> {
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.onIntent(A1LearningIntent.StartNextCluster) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(LearnTokens.RadiusSm),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Начать урок",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = LearnTokens.FontSizeBody,
                    )
                }
                if (state.weakLemmasCount > 0) {
                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                    OutlinedButton(
                        onClick = { vm.onIntent(A1LearningIntent.StartReviewSession) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(LearnTokens.RadiusSm),
                        border = BorderStroke(LearnTokens.BorderThin, colors.stroke),
                    ) {
                        Icon(
                            Icons.Filled.Refresh, null,
                            tint = colors.textMid,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Повторить ${state.weakLemmasCount} ${Plural.word(state.weakLemmasCount)}",
                            color = colors.textMid,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = LearnTokens.FontSizeCaption,
                        )
                    }
                }
            }
        }
        state.weakLemmasCount > 0 -> {
            Button(
                onClick = { vm.onIntent(A1LearningIntent.StartReviewSession) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Повторить ${state.weakLemmasCount} ${Plural.word(state.weakLemmasCount)}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = LearnTokens.FontSizeBody,
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LearnTokens.RadiusSm))
                    .background(colors.successSoft)
                    .padding(LearnTokens.PaddingMd),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Все уроки пройдены",
                    fontSize = LearnTokens.FontSizeBody,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.success,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// DIALOGS
// ═══════════════════════════════════════════════════════════
@Composable
private fun SessionFinishedDialog(
    quality: Int,
    feedback: String,
    lemmasProduced: Int,
    lemmasFailed: Int,
    isReviewMode: Boolean,
    onContinue: () -> Unit,
) {
    val colors = learnColors()
    val color = when {
        quality >= 6 -> colors.success
        quality >= 4 -> colors.warn
        else -> colors.error
    }
    AlertDialog(
        onDismissRequest = { onContinue() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = null,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$quality", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.height(LearnTokens.PaddingMd))
                Text(
                    if (isReviewMode) "Повторение завершено" else "Сессия завершена",
                    fontSize = LearnTokens.FontSizeTitle,
                    fontWeight = FontWeight.Bold,
                    color = colors.textHi,
                )
                Spacer(Modifier.height(LearnTokens.PaddingSm))
                Row(horizontalArrangement = Arrangement.spacedBy(LearnTokens.PaddingLg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = colors.success, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$lemmasProduced", color = colors.success, fontWeight = FontWeight.Bold,
                            fontSize = LearnTokens.FontSizeBodyLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cancel, null, tint = colors.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$lemmasFailed", color = colors.error, fontWeight = FontWeight.Bold,
                            fontSize = LearnTokens.FontSizeBodyLarge)
                    }
                }
                if (feedback.isNotBlank()) {
                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                    Text(
                        feedback,
                        fontSize = LearnTokens.FontSizeBody,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        color = colors.textMid,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
            ) {
                Text("Продолжить", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(LearnTokens.RadiusLg),
    )
}

@Composable
private fun A1CompletedDialog(onClose: () -> Unit) {
    val colors = learnColors()
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("A1 пройден", fontWeight = FontWeight.Bold, color = colors.textHi)
        },
        text = {
            Text(
                "Поздравляем! Все слова и правила A1 освоены. Вы готовы к A2.",
                lineHeight = 20.sp,
                color = colors.textMid,
            )
        },
        confirmButton = {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
            ) {
                Text("Отлично", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(LearnTokens.RadiusLg),
    )
}
