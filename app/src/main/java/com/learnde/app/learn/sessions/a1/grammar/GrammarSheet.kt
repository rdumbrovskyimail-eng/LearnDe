// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/grammar/GrammarSheet.kt
//
// Modal-bottom-sheet со списком открытых правил грамматики A1.
// Открывается тапом по карточке прогресса грамматики в A1LearningScreen.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.grammar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.GrammarRuleA1Entity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarSheet(
    onDismiss: () -> Unit,
    vm: GrammarSheetViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.School,
                    null,
                    tint = Color(0xFFAB47BC),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Справочник грамматики A1",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Открыто ${state.introducedCount}/${state.totalCount} правил",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            
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
    val totalAttempts = rule.timesAppliedCorrectly + rule.timesFailedOnThis
    val successRate = if (totalAttempts > 0) {
        (rule.timesAppliedCorrectly * 100) / totalAttempts
    } else null
    
    val accentColor = when {
        !rule.wasIntroduced -> Color(0xFF9E9E9E)
        successRate == null -> Color(0xFF1E88E5)
        successRate >= 75 -> Color(0xFF43A047)
        successRate >= 50 -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (rule.wasIntroduced) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.nameRu,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    rule.nameDe,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Serif,
                )
            }
            successRate?.let {
                Text(
                    "$it%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (rule.wasIntroduced) {
            Spacer(Modifier.height(8.dp))
            Text(
                rule.shortExplanation,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (totalAttempts > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Применил правильно: ${rule.timesAppliedCorrectly} · Ошибок: ${rule.timesFailedOnThis}",
                    fontSize = 10.sp,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}