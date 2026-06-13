package com.learnde.app.presentation.learn.v2.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.learnde.app.presentation.learn.theme.LearnColors
import com.learnde.app.presentation.learn.v2.GeminiShape

/** Лёгкая UI-проекция текущего шага для карточки. */
data class WordFocus(
    /** Заголовок-чип: «Новое слово», «Повторение», «Диалог», «Грамматика», «Финал»… */
    val kindLabel: String,
    /** Немецкое слово/фраза (может быть пустым для не-лексических шагов). */
    val wordDe: String,
    /** Артикль der/die/das (если есть). */
    val article: String? = null,
    /** Перевод/подсказка на русском. */
    val wordRu: String? = null,
    /** Инструкция/описание шага (для диалога, грамматики, финала). */
    val instruction: String? = null,
    /** Цветовой акцент статуса. */
    val tone: FocusTone = FocusTone.NEUTRAL,
)

enum class FocusTone { NEUTRAL, NEW, REVIEW, DIALOG, GRAMMAR, FINALE }

@Composable
fun WordFocusCard(
    focus: WordFocus,
    colors: LearnColors,
    modifier: Modifier = Modifier,
) {
    val stripColor = when (focus.tone) {
        FocusTone.NEW -> colors.accent
        FocusTone.REVIEW -> colors.success
        FocusTone.DIALOG -> colors.accent.copy(alpha = 0.6f)
        FocusTone.GRAMMAR -> colors.warning
        FocusTone.FINALE -> colors.accent
        FocusTone.NEUTRAL -> colors.outline
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = GeminiShape.card,
        color = colors.surfaceRaised,
        tonalElevation = 0.dp,
    ) {
        Row(Modifier.height(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            // Цветная полоска статуса слева.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                    .background(stripColor)
            )

            Column(Modifier.padding(18.dp).fillMaxWidth()) {
                // Чип типа шага.
                KindChip(label = focus.kindLabel, color = stripColor, colors = colors)

                AnimatedContent(
                    targetState = focus,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "wordFocus",
                ) { f ->
                    Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        if (f.wordDe.isNotBlank()) {
                            // Лексический шаг: артикль + слово + перевод.
                            Row(verticalAlignment = Alignment.Bottom) {
                                if (!f.article.isNullOrBlank()) {
                                    Text(
                                        text = f.article + " ",
                                        color = colors.textSecondary,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(
                                    text = f.wordDe,
                                    color = colors.textPrimary,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (!f.wordRu.isNullOrBlank()) {
                                Text(
                                    text = f.wordRu,
                                    color = colors.textSecondary,
                                    fontSize = 17.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }

                        if (!f.instruction.isNullOrBlank()) {
                            Text(
                                text = f.instruction,
                                color = if (f.wordDe.isBlank()) colors.textPrimary else colors.textSecondary,
                                fontSize = if (f.wordDe.isBlank()) 19.sp else 15.sp,
                                fontWeight = if (f.wordDe.isBlank()) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier.padding(top = if (f.wordDe.isBlank()) 0.dp else 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KindChip(label: String, color: androidx.compose.ui.graphics.Color, colors: LearnColors) {
    Surface(
        shape = GeminiShape.chip,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = label.uppercase(),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}