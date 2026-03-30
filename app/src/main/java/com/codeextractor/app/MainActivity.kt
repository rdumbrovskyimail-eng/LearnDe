package com.codeextractor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.codeextractor.app.presentation.navigation.AppNavGraph
import com.codeextractor.app.presentation.theme.GeminiLiveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeminiLiveTheme {
                AppNavGraph()
            }
        }
    }
}
