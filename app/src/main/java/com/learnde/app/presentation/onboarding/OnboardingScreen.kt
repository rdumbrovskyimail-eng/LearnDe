// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/onboarding/OnboardingScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>
) : ViewModel() {

    var isLoaded by mutableStateOf(false)
        private set
    var initialName by mutableStateOf("")
        private set
    var initialGoals by mutableStateOf("")
        private set
    var initialTopics by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            initialName = settings.userName
            initialGoals = settings.learningGoals
            initialTopics = settings.learningTopics
            isLoaded = true
        }
    }

    fun saveAndContinue(name: String, goals: String, topics: String, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsStore.updateData {
                it.copy(
                    userName = name.trim(),
                    learningGoals = goals.trim(),
                    learningTopics = topics.trim()
                )
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    if (!viewModel.isLoaded) return

    var name by remember { mutableStateOf(viewModel.initialName) }
    var goals by remember { mutableStateOf(viewModel.initialGoals) }
    var topics by remember { mutableStateOf(viewModel.initialTopics) }

    // Жестко задаем белый фон и темный текст (Стиль Gemini)
    Scaffold(containerColor = Color.White) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Deutsch lernen",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A) // Почти черный
            )
            
            Text(
                text = "Настройте своего ИИ-преподавателя",
                fontSize = 16.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 40.dp)
            )

            // Поле 1
            GeminiStyleTextField(
                value = name,
                onValueChange = { name = it },
                label = "Введите свое имя на немецком!",
                supportingText = "Напишите латиницей свое имя"
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Поле 2
            GeminiStyleTextField(
                value = goals,
                onValueChange = { goals = it },
                label = "Цель изучения немецкого (можно несколько):",
                supportingText = "От этого будет зависеть основной фокус генерации обучения",
                singleLine = false,
                minLines = 2
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Поле 3
            GeminiStyleTextField(
                value = topics,
                onValueChange = { topics = it },
                label = "Темы, которые интересны для обсуждения:",
                supportingText = "Генерация будет делать упор на эти темы (можно пусто)",
                singleLine = false,
                minLines = 2
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(40.dp))

            // Белая минималистичная кнопка
            OutlinedButton(
                onClick = { viewModel.saveAndContinue(name, goals, topics, onNavigateToSettings) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) {
                Text(
                    text = "Перейти к настройкам",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GeminiStyleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF1A73E8),
            unfocusedBorderColor = Color(0xFFDADCE0),
            focusedLabelColor = Color(0xFF1A73E8),
            unfocusedLabelColor = Color(0xFF5F6368),
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.DarkGray,
            focusedSupportingTextColor = Color.Gray,
            unfocusedSupportingTextColor = Color.Gray,
            cursorColor = Color(0xFF1A73E8)
        ),
        shape = RoundedCornerShape(12.dp)
    )
}