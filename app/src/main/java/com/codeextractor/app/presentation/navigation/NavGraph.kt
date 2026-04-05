package com.codeextractor.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codeextractor.app.presentation.editor.ModelEditorScreen
import com.codeextractor.app.presentation.voice.VoiceScreen

object Routes {
    const val VOICE  = "voice"
    const val EDITOR = "editor"
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController    = navController,
        startDestination = Routes.VOICE,
    ) {
        composable(
            route            = Routes.VOICE,
            enterTransition  = { fadeIn(tween(300)) },
            exitTransition   = { fadeOut(tween(300)) },
        ) {
            VoiceScreen(
                onOpenEditor = { navController.navigate(Routes.EDITOR) }
            )
        }

        composable(
            route            = Routes.EDITOR,
            enterTransition  = { fadeIn(tween(300)) },
            exitTransition   = { fadeOut(tween(300)) },
        ) {
            ModelEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}