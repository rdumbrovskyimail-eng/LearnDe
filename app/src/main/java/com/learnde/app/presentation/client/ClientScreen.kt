package com.learnde.app.presentation.client

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.presentation.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    navController: NavController,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.toggleMic()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemini Live", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Status Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val statusColor = when {
                    state.isConnected -> Color(0xFF4CAF50)
                    state.isConnecting -> Color(0xFFFFC107)
                    else -> Color(0xFF9E9E9E)
                }
                val statusText = when {
                    state.isConnected -> "Подключено"
                    state.isConnecting -> "Подключение..."
                    else -> "Отключено"
                }
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(statusText, fontSize = 14.sp, color = Color.Gray)
                
                Spacer(modifier = Modifier.weight(1f))
                if (state.isAiSpeaking) {
                    Text("ИИ говорит...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            state.error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Color.Red, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transcript
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.transcript) { msg ->
                    val isUser = msg.role == ConversationMessage.ROLE_USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = msg.text, 
                                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.toggleConnection() },
                    modifier = Modifier.height(56.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isConnected || state.isConnecting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (state.isConnected || state.isConnecting) "Отключить" else "Подключить", fontSize = 16.sp)
                }
                
                Spacer(modifier = Modifier.width(16.dp))

                FloatingActionButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.toggleMic()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    containerColor = if (state.isMicActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.isMicActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(
                        if (state.isMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = "Микрофон"
                    )
                }
            }
        }
    }
}