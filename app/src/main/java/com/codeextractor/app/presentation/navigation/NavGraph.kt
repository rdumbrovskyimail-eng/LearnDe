package com.codeextractor.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codeextractor.app.presentation.editor.ModelEditorScreen
import com.codeextractor.app.presentation.settings.SettingsScreen
import com.codeextractor.app.presentation.voice.VoiceScreen

object Routes {
    const val SETTINGS = "settings"
    const val VOICE    = "voice"
    const val EDITOR   = "editor"
}

/**
 * Маппинг голоса → пол аватара.
 * 1 = мужской (test.glb), 2 = женский (test2.glb)
 */
object VoiceGender {
    private val MALE_VOICES = setOf("Puck", "Charon", "Fenrir", "Orus")
    private val FEMALE_VOICES = setOf("Kore", "Aoede", "Leda", "Zephyr")

    fun avatarIndexForVoice(voiceId: String): Int =
        if (voiceId in MALE_VOICES) 1 else 2
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController    = navController,
        startDestination = Routes.SETTINGS, // ← Настройки при старте
    ) {
        composable(
            route            = Routes.SETTINGS,
            enterTransition  = { fadeIn(tween(300)) },
            exitTransition   = { fadeOut(tween(200)) },
        ) {
            SettingsScreen(
                onStartSession = { navController.navigate(Routes.VOICE) {
                    popUpTo(Routes.SETTINGS) { inclusive = false }
                } }
            )
        }

        composable(
            route            = Routes.VOICE,
            enterTransition  = { slideInHorizontally(tween(300)) { -it } },
            exitTransition   = { slideOutHorizontally(tween(300)) { -it } },
        ) {
            VoiceScreen(
                onOpenEditor   = { navController.navigate(Routes.EDITOR) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
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
