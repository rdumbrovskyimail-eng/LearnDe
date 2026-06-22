// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1book/A1BookScreen.kt
//
// КНИЖНЫЙ КУРС A1.1 — три экрана:
//   1) Список уроков.
//   2) «Учебник»: цели, грамматика с таблицами и примерами, лексика,
//      превью практики. Здесь ученик СНАЧАЛА читает и разбирает правило.
//   3) Голосовой урок: тот же урок отрабатывается с репетитором вслух.
// Стиль — монохромный (серый/белый, GPT-like) через learnColors().
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    val inDetail = ui.openLesson != null || ui.openLoading || ui.openError

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) learnCoreViewModel.onIntent(LearnCoreIntent.Start(BOOK_SESSION_ID))
    }

    fun startVoice(n: Int) {
        vm.select(n)
        val hasMic = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) learnCoreViewModel.onIntent(LearnCoreIntent.Start(BOOK_SESSION_ID))
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Назад: из урока-голоса → стоп (остаёмся в учебнике); из учебника → к списку;
    // из списка → выход с экрана.
    BackHandler(enabled = active || inDetail) {
        when {
            active -> learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
            inDetail -> vm.closeLesson()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = LearnTokens.PaddingLg),
    ) {
        val title: String
        val subtitle: String
        when {
            active -> {
                title = ui.openLesson?.let { "Урок ${it.nummer}" } ?: "Голосовой урок"
                subtitle = ui.openLesson?.themaRu ?: "Говорите вслух с репетитором"
            }
            inDetail -> {
                title = ui.openLesson?.let { "Урок ${it.nummer}" } ?: "Урок"
                subtitle = ui.openLesson?.themaDe ?: " "
            }
            else -> {
                title = "A1 · По учебнику"
                subtitle = "Schritte A1.1 · правила, примеры, практика"
            }
        }

        HeaderBar(
            title = title,
            subtitle = subtitle,
            colors = colors,
            onBack = {
                when {
                    active -> learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    inDetail -> vm.closeLesson()
                    else -> onBack()
                }
            },
        )

        when {
            active -> SessionView(
                core = core,
                colors = colors,
                onToggleMic = { learnCoreViewModel.onIntent(LearnCoreIntent.ToggleMic) },
                onStop = { learnCoreViewModel.onIntent(LearnCoreIntent.Stop) },
            )

            inDetail -> LessonDetail(
                lesson = ui.openLesson,
                loading = ui.openLoading,
                error = ui.openError,
                colors = colors,
                onStart = { ui.openLesson?.let { startVoice(it.nummer) } },
            )

            else -> LessonList(
                lessons = ui.lessons,
                loading = ui.loading,
                colors = colors,
                onPick = { vm.open(it) },
            )
        }
    }
}

// ─────────────────────────── ШАПКА ───────────────────────────

@Composable
private fun HeaderBar(
    title: String,
    subtitle: String,
    colors: LearnColors,
    onBack: () -> Unit,
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
                .clickable { onBack() }
                .padding(horizontal = LearnTokens.PaddingSm),
        )
        Spacer(Modifier.width(LearnTokens.PaddingSm))
        Column {
            Text(
                title,
                fontSize = LearnTokens.FontSizeTitle,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

// ─────────────────────────── СПИСОК УРОКОВ ───────────────────────────

@Composable
private fun LessonList(
    lessons: List<A1BookLessonMeta>,
    loading: Boolean,
    colors: LearnColors,
    onPick: (Int) -> Unit,
) {
    if (loading) {
        CenterNote("Загрузка уроков…", colors); return
    }
    if (lessons.isEmpty()) {
        CenterNote("Уроки не найдены. Проверь файлы в assets/a1_book/.", colors); return
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
                        color = colors.textPrimary,
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
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${l.grammatikTitel.size} правил · ${l.anzahlVoice} заданий",
                        color = colors.textTertiary,
                        fontSize = LearnTokens.FontSizeCaption,
                    )
                }
                Text("›", color = colors.textTertiary, fontSize = 24.sp)
            }
        }
    }
}

// ─────────────────────────── УЧЕБНИК (детали урока) ───────────────────────────

@Composable
private fun ColumnScope.LessonDetail(
    lesson: A1BookLesson?,
    loading: Boolean,
    error: Boolean,
    colors: LearnColors,
    onStart: () -> Unit,
) {
    if (loading) { CenterNote("Открываю урок…", colors); return }
    if (error || lesson == null) {
        CenterNote("Не удалось открыть урок. Вернись и попробуй снова.", colors); return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingMd),
        contentPadding = PaddingValues(top = LearnTokens.PaddingSm, bottom = LearnTokens.PaddingLg),
    ) {
        // Цели урока
        if (lesson.lernziele.isNotEmpty()) {
            item { SectionLabel("Цели урока", colors) }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(LearnTokens.RadiusLg))
                        .background(colors.surfaceSunken)
                        .padding(LearnTokens.PaddingLg),
                    verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm),
                ) {
                    lesson.lernziele.forEach { z ->
                        Row {
                            Text("•  ", color = colors.textSecondary, fontSize = LearnTokens.FontSizeBody)
                            Text(z, color = colors.textPrimary, fontSize = LearnTokens.FontSizeBody)
                        }
                    }
                }
            }
        }

        // Грамматика
        if (lesson.grammatik.isNotEmpty()) {
            item { SectionLabel("Грамматика", colors) }
            items(lesson.grammatik) { g -> GrammarBlock(g, colors) }
        }

        // Лексика
        if (lesson.wortschatz.isNotEmpty()) {
            item { SectionLabel("Слова урока · ${lesson.wortschatz.size}", colors) }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(LearnTokens.RadiusLg))
                        .border(LearnTokens.BorderThin, colors.divider, RoundedCornerShape(LearnTokens.RadiusLg)),
                ) {
                    lesson.wortschatz.forEachIndexed { i, w ->
                        VocabRow(w, colors)
                        if (i != lesson.wortschatz.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(LearnTokens.BorderThin)
                                    .background(colors.divider),
                            )
                        }
                    }
                }
            }
        }

        // Практика (превью — без правильных ответов)
        if (lesson.voiceAufgaben.isNotEmpty()) {
            item { SectionLabel("Практика в голосовом уроке · ${lesson.voiceAufgaben.size}", colors) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm)) {
                    lesson.voiceAufgaben.forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                                .background(colors.surfaceSunken)
                                .padding(LearnTokens.PaddingMd),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(LearnTokens.PaddingSm))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.anweisungRu.ifBlank { "Задание" },
                                    color = colors.textPrimary,
                                    fontSize = LearnTokens.FontSizeBody,
                                )
                                val hint = t.promptRu ?: t.promptDe
                                if (!hint.isNullOrBlank()) {
                                    Text(
                                        hint,
                                        color = colors.textSecondary,
                                        fontSize = LearnTokens.FontSizeCaption,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(LearnTokens.PaddingXs)) }
    }

    // Нижняя кнопка старта голосового урока — всегда видна.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LearnTokens.PaddingMd)
            .clip(RoundedCornerShape(LearnTokens.RadiusLg))
            .background(colors.accent)
            .clickable { onStart() }
            .padding(vertical = LearnTokens.PaddingLg),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            Text(
                "Начать голосовой урок",
                color = colors.onAccent,
                fontSize = LearnTokens.FontSizeBodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GrammarBlock(g: A1BookGrammar, colors: LearnColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusLg))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.divider, RoundedCornerShape(LearnTokens.RadiusLg))
            .padding(LearnTokens.PaddingLg),
        verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm),
    ) {
        Text(
            g.titelRu.ifBlank { g.titelDe },
            color = colors.textPrimary,
            fontSize = LearnTokens.FontSizeBodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (g.titelDe.isNotBlank() && g.titelDe != g.titelRu) {
            Text(g.titelDe, color = colors.textTertiary, fontSize = LearnTokens.FontSizeCaption)
        }
        if (g.erklaerungRu.isNotBlank()) {
            Text(g.erklaerungRu, color = colors.textPrimary, fontSize = LearnTokens.FontSizeBody)
        }

        if (g.tableLines.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                    .background(colors.surfaceSunken)
                    .padding(LearnTokens.PaddingMd),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                g.tableLines.forEach { line ->
                    Text(
                        line,
                        color = colors.textPrimary,
                        fontSize = LearnTokens.FontSizeBody,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (g.beispiele.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                g.beispiele.forEach { (de, ru) ->
                    Row {
                        Text(
                            de,
                            color = colors.textPrimary,
                            fontSize = LearnTokens.FontSizeBody,
                            fontWeight = FontWeight.Medium,
                        )
                        if (ru.isNotBlank()) {
                            Text(
                                "  — $ru",
                                color = colors.textSecondary,
                                fontSize = LearnTokens.FontSizeBody,
                            )
                        }
                    }
                }
            }
        }

        if (g.merkeRu.isNotBlank() && g.merkeRu != "—") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                    .background(colors.warningSoft)
                    .padding(LearnTokens.PaddingMd),
            ) {
                Text("★  ", color = colors.warning, fontSize = LearnTokens.FontSizeBody)
                Text(
                    g.merkeRu,
                    color = colors.textPrimary,
                    fontSize = LearnTokens.FontSizeBody,
                )
            }
        }
    }
}

@Composable
private fun VocabRow(w: A1BookWord, colors: LearnColors) {
    val art = w.artikel?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
    val pl = w.plural?.takeIf { it.isNotBlank() && it != "—" }?.let { " · pl. $it" } ?: ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LearnTokens.PaddingLg, vertical = LearnTokens.PaddingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$art${w.de}",
                color = colors.textPrimary,
                fontSize = LearnTokens.FontSizeBody,
                fontWeight = FontWeight.Medium,
            )
            if (pl.isNotBlank()) {
                Text(pl.trim(), color = colors.textTertiary, fontSize = LearnTokens.FontSizeCaption)
            }
        }
        Spacer(Modifier.width(LearnTokens.PaddingMd))
        Text(
            w.ru,
            color = colors.textSecondary,
            fontSize = LearnTokens.FontSizeBody,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SectionLabel(text: String, colors: LearnColors) {
    Text(
        text.uppercase(),
        color = colors.textTertiary,
        fontSize = LearnTokens.FontSizeCaption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = LearnTokens.CapsLetterSpacing,
        modifier = Modifier.padding(top = LearnTokens.PaddingSm),
    )
}

@Composable
private fun CenterNote(text: String, colors: LearnColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = colors.textSecondary,
            fontSize = LearnTokens.FontSizeBody,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(LearnTokens.PaddingXl),
        )
    }
}

// ─────────────────────────── ГОЛОСОВОЙ УРОК ───────────────────────────

@Composable
private fun ColumnScope.SessionView(
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
        core.isAiSpeaking -> colors.textSecondary
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
            color = colors.textPrimary,
            fontSize = LearnTokens.FontSizeBody,
            modifier = Modifier
                .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                .background(if (isUser) colors.bubbleUser else colors.bubbleTutor)
                .padding(horizontal = LearnTokens.PaddingMd, vertical = LearnTokens.PaddingSm),
        )
    }
}
