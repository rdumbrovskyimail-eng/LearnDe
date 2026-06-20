// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1book/A1BookScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1book

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.theme.LearnColors
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

private const val BOOK_SESSION_ID = "a1_book"

@Composable
fun A1BookScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    vm: A1BookViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val core by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val colors = learnColors()
    val ctx = LocalContext.current

    val active = core.sessionId == BOOK_SESSION_ID &&
        core.connectionStatus != LearnConnectionStatus.Disconnected

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) learnCoreViewModel.onIntent(LearnCoreIntent.Start(BOOK_SESSION_ID))
    }

    fun launchLesson(n: Int) {
        vm.select(n)
        val hasMic = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) learnCoreViewModel.onIntent(LearnCoreIntent.Start(BOOK_SESSION_ID))
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    BackHandler(enabled = active) {
        learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = LearnTokens.PaddingLg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = LearnTokens.PaddingMd),
        ) {
            Text(
                "‹",
                fontSize = 28.sp,
                color = colors.textPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        if (active) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                        onBack()
                    }
                    .padding(horizontal = LearnTokens.PaddingSm),
            )
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            Column {
                Text(
                    "A1 · По учебнику",
                    fontSize = LearnTokens.FontSizeTitle,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    "Schritte A1.1 · уроки, правила, диалоги",
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textSecondary,
                )
            }
        }

        if (!active) {
            LessonList(lessons = ui.lessons, loading = ui.loading, colors = colors) { launchLesson(it) }
        } else {
            SessionView(
                core = core,
                colors = colors,
                onToggleMic = { learnCoreViewModel.onIntent(LearnCoreIntent.ToggleMic) },
                onStop = { learnCoreViewModel.onIntent(LearnCoreIntent.Stop) },
            )
        }
    }
}

@Composable
private fun LessonList(
    lessons: List<A1BookLessonMeta>,
    loading: Boolean,
    colors: LearnColors,
    onPick: (Int) -> Unit,
) {
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Загрузка уроков…", color = colors.textSecondary, fontSize = LearnTokens.FontSizeBody)
        }
        return
    }
    if (lessons.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Уроки не найдены. Проверь файлы в assets/a1_book/.",
                color = colors.textSecondary,
                fontSize = LearnTokens.FontSizeBody,
            )
        }
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingMd),
        contentPadding = PaddingValues(bottom = LearnTokens.PaddingXl),
    ) {
        items(lessons) { l ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LearnTokens.RadiusLg))
                    .background(colors.surface)
                    .border(LearnTokens.BorderThin, colors.divider, RoundedCornerShape(LearnTokens.RadiusLg))
                    .clickable { onPick(l.nummer) }
                    .padding(LearnTokens.PaddingLg),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        l.nummer.toString(),
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = LearnTokens.FontSizeBodyLarge,
                    )
                }
                Spacer(Modifier.width(LearnTokens.PaddingMd))
                Column(Modifier.weight(1f)) {
                    Text(
                        l.themaRu,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = LearnTokens.FontSizeBodyLarge,
                    )
                    Text(
                        l.themaDe,
                        color = colors.textSecondary,
                        fontSize = LearnTokens.FontSizeCaption,
                    )
                    val rules = l.grammatikTitel.size
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$rules правил · ${l.anzahlVoice} заданий",
                        color = colors.textTertiary,
                        fontSize = LearnTokens.FontSizeCaption,
                    )
                }
                Text("‣", color = colors.accent, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun SessionView(
    core: com.learnde.app.learn.core.LearnCoreState,
    colors: LearnColors,
    onToggleMic: () -> Unit,
    onStop: () -> Unit,
) {
    val connecting = core.isPreparingSession ||
        core.connectionStatus == LearnConnectionStatus.Connecting ||
        core.connectionStatus == LearnConnectionStatus.Negotiating

    val caption = when {
        core.error != null -> "Ошибка соединения — вернись и попробуй снова"
        connecting -> "Подключаюсь к уроку…"
        core.isAiSpeaking -> "Репетитор говорит…"
        core.isMicActive -> "Слушаю — говори"
        else -> "Готов — нажми микрофон и говори"
    }
    val dotColor = when {
        core.error != null -> colors.danger
        core.isAiSpeaking -> colors.accent
        core.isMicActive -> colors.success
        else -> colors.textTertiary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = LearnTokens.PaddingSm),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(LearnTokens.PaddingSm))
        Text(caption, color = colors.textSecondary, fontSize = LearnTokens.FontSizeBody)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm),
        contentPadding = PaddingValues(vertical = LearnTokens.PaddingMd),
    ) {
        items(core.transcript) { msg -> Bubble(msg, colors) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingLg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Завершить урок",
            color = colors.danger,
            fontSize = LearnTokens.FontSizeBody,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(LearnTokens.RadiusSm))
                .clickable { onStop() }
                .padding(horizontal = LearnTokens.PaddingMd, vertical = LearnTokens.PaddingSm),
        )

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (core.isMicActive) colors.accentSoft else colors.surfaceRaised)
                .border(
                    LearnTokens.BorderMedium,
                    if (core.isMicActive) colors.accent else colors.divider,
                    CircleShape,
                )
                .clickable { onToggleMic() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Микрофон",
                tint = if (core.isMicActive) colors.accent else colors.textTertiary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun Bubble(msg: ConversationMessage, colors: LearnColors) {
    val isUser = msg.role == ConversationMessage.ROLE_USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = msg.text,
            color = if (isUser) colors.onAccent else colors.textPrimary,
            fontSize = LearnTokens.FontSizeBody,
            modifier = Modifier
                .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                .background(if (isUser) colors.accent else colors.bubbleTutor)
                .padding(horizontal = LearnTokens.PaddingMd, vertical = LearnTokens.PaddingSm),
        )
    }
}
