// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/grammar/GrammarSheet.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.grammar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.GrammarRuleA1Entity
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarSheet(
    onDismiss: () -> Unit,
    vm: GrammarSheetViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = learnColors()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LearnTokens.PaddingLg)
                .padding(bottom = LearnTokens.PaddingXl),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.MenuBook,
                    null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Грамматика A1",
                    fontSize = LearnTokens.FontSizeTitle,
                    fontWeight = FontWeight.Bold,
                    color = colors.textHi,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Закрыть",
                        tint = colors.textMid
                    )
                }
            }
            Text(
                "Открыто ${state.introducedCount} из ${state.totalCount} правил",
                fontSize = LearnTokens.FontSizeCaption,
                color = colors.textLow,
            )
            Spacer(Modifier.height(LearnTokens.PaddingMd))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 600.dp),
            ) {
                items(state.rules, key = { it.id }) { rule ->
                    GrammarRuleCard(rule)
                }
            }
        }
    }
}

@Composable
private fun GrammarRuleCard(rule: GrammarRuleA1Entity) {
    val colors = learnColors()
    val totalAttempts = rule.timesAppliedCorrectly + rule.timesFailedOnThis
    val successRate = if (totalAttempts > 0) {
        (rule.timesAppliedCorrectly * 100) / totalAttempts
    } else null

    val accent: Color = when {
        !rule.wasIntroduced -> colors.textLow
        successRate == null -> colors.accent
        successRate >= 75 -> colors.success
        successRate >= 50 -> colors.warn
        else -> colors.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, accent.copy(alpha = 0.25f), RoundedCornerShape(LearnTokens.RadiusSm))
            .padding(LearnTokens.PaddingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (rule.wasIntroduced) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.nameRu,
                    fontSize = LearnTokens.FontSizeBody,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textHi,
                )
                Text(
                    rule.nameDe,
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textMid,
                    fontWeight = FontWeight.Medium,
                )
            }
            successRate?.let {
                Text(
                    "$it%",
                    fontSize = LearnTokens.FontSizeBody,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }
        if (rule.wasIntroduced && rule.shortExplanation.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                rule.shortExplanation,
                fontSize = LearnTokens.FontSizeCaption,
                lineHeight = 17.sp,
                color = colors.textMid,
            )
            if (totalAttempts > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Применил правильно: ${rule.timesAppliedCorrectly} · Ошибок: ${rule.timesFailedOnThis}",
                    fontSize = 10.sp,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
