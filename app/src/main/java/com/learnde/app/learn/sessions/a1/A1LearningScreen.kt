// ═══════════════════════════════════════════════════════════
// A1LearningScreen v7 (Voice-First, Auto-Scroll, Mic-Level)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.*
import com.learnde.app.presentation.learn.components.*
import com.learnde.app.presentation.learn.theme.*

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

    var menuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var showGrammarSheet by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start(if (state.isReviewMode) "a1_review" else "a1_situation"))
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isReviewMode) "Повторение" else "Обучение A1", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Карта") }, onClick = { menuExpanded = false; onOpenCourseMap() })
                        DropdownMenuItem(text = { Text("Словарь") }, onClick = { menuExpanded = false; onOpenVocabulary() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = LearnTokens.PaddingLg)) {
            // Inline Loader
            AnimatedVisibility(learnState.isPreparingSession) {
                InlineLoadingBar(Modifier.fillMaxWidth().padding(vertical = LearnTokens.PaddingSm))
            }

            // Progress
            CompactProgressRow(state)
            
            // Cluster Card
            if (!state.isReviewMode) {
                state.currentCluster?.let { CompactClusterCard(it, state.sessionActive, detailsExpanded) { detailsExpanded = !detailsExpanded } }
            }

            // Chat (Auto-scroll)
            ChatSection(
                transcript = learnState.transcript,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
                modifier = Modifier.weight(1f)
            )

            // Action Buttons
            BottomActionButton(state, vm, learnCoreViewModel)
        }
    }
}

@Composable
private fun ChatSection(transcript: List<ConversationMessage>, isAiSpeaking: Boolean, isMicActive: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val isPinnedToBottom by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == transcript.lastIndex } }

    LaunchedEffect(transcript.size) {
        if (isPinnedToBottom) {
            listState.animateScrollToItem(transcript.size.coerceAtLeast(0))
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Диалог", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            MicLevelIndicator(isMicActive)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            items(transcript) { msg -> ChatBubble(msg) }
        }
    }
}

@Composable
private fun MicLevelIndicator(isMicActive: Boolean) {
    val infinite = rememberInfiniteTransition(label = "mic")
    val scale by infinite.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "scale")
    if (isMicActive) {
        Icon(imageVector = Icons.Filled.Mic, contentDescription = null, tint = Color.Red, modifier = Modifier.scale(scale))
    }
}

@Composable
private fun ChatBubble(msg: ConversationMessage) {
    val isUser = msg.role == ConversationMessage.ROLE_USER
    Surface(
        color = if (isUser) Color.LightGray else Color.White,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(0.8f).then(if (isUser) Modifier.wrapContentWidth(Alignment.End) else Modifier)
    ) {
        Text(msg.text, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun CompactProgressRow(state: A1LearningState) { /* ... implementation ... */ }
@Composable
private fun CompactClusterCard(cluster: Any, active: Boolean, expanded: Boolean, onToggle: () -> Unit) { /* ... implementation ... */ }
@Composable
private fun BottomActionButton(state: A1LearningState, vm: A1LearningViewModel, core: LearnCoreViewModel) { /* ... implementation ... */ }
