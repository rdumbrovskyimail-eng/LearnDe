// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/codeextractor/app/presentation/functions/FunctionsTestScreen.kt
//
// Экран тестирования 10 функций.
//
// СТРУКТУРА:
//   ┌──────────────────────────────────────────────┐
//   │  ← Тест функций                              │  ← TopBar
//   ├──────────────────────────────────────────────┤
//   │ [1] [2] [3] [4] [5]  ← 10 планшетов          │
//   │ [6] [7] [8] [9] [10]   (тап = локальный тест)│
//   ├──────────────────────────────────────────────┤
//   │ ● ● ● ● ● ● ● ● ● ●    ← 10 лампочек         │
//   ├──────────────────────────────────────────────┤
//   │   (описания активной функции)                │
//   ├──────────────────────────────────────────────┤
//   │  Сейчас выполняется: Функция 4 — …           │  ← тает
//   └──────────────────────────────────────────────┘
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.functions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionsTestScreen(
    onBack: () -> Unit,
    viewModel: FunctionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fns = viewModel.functions
    val palette = viewModel.palette

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Тест функций",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            Text(
                "Скажите «Выполни функцию N» во время стриминга — " +
                        "модель вызовет соответствующую функцию, " +
                        "а здесь загорятся её цветные лампочки.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            // ─── Сетка планшетов ───
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
    items(fns, key = { it.number }) { fn ->
        val isActive = state.lastExecutedNumber == fn.number
        FunctionTile(
            number = fn.number,
                        title = fn.title,
                        isActive = isActive,
                        onClick = { viewModel.onFunctionExecuted(fn) }
                    )
                }
            }

            // ─── 10 лампочек ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(18.dp)
                    )
                    .padding(vertical = 20.dp, horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    palette.forEachIndexed { idx, color ->
                        LightBulb(
                            color = color,
                            lit = idx in state.activeLightIds
                        )
                    }
                }
            }

            // ─── Описание активной функции ───
            val activeFn = state.lastExecutedNumber?.let { n -> fns.firstOrNull { it.number == n } }
            if (activeFn != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            activeFn.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            activeFn.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ─── Нижняя строка «Сейчас выполняется: …» ───
            Text(
                text = state.statusText.ifBlank { " " },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(state.statusAlpha)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────
// COMPONENTS
// ─────────────────────────────────────────────

@Composable
private fun FunctionTile(
    number: Int,
    title: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "tileScale"
    )
    val bg = if (isActive) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surface
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .shadow(if (isActive) 4.dp else 1.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            number.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
        Text(
            title.removePrefix("Функция ").let { "F$it" }.take(4),
            fontSize = 10.sp,
            color = fg.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun LightBulb(color: Color, lit: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (lit) 1f else 0.12f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 350f),
        label = "bulbAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (lit) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 350f),
        label = "bulbScale"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp)
    ) {
        // Halo при активности
        if (lit) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.25f))
            )
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (lit) color else color.copy(alpha = 0.18f)
                )
                .border(
                    width = 1.dp,
                    color = if (lit) color else Color.Black.copy(alpha = 0.2f),
                    shape = CircleShape
                )
                .alpha(alpha)
        )
    }
}