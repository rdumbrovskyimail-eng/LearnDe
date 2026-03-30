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
import com.codeextractor.app.presentation.avatartest.AvatarTestScreen
import com.codeextractor.app.presentation.voice.VoiceScreen

object Routes {
    const val VOICE       = "voice"
    const val AVATAR_TEST = "avatar_test"
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
            route          = Routes.VOICE,
            enterTransition  = { fadeIn(tween(300)) },
            exitTransition   = { slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(300)) },
            popEnterTransition = { slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(300)) },
            popExitTransition  = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) },
        ) {
            VoiceScreen(
                onNavigateToAvatarTest = { navController.navigate(Routes.AVATAR_TEST) }
            )
        }

        composable(
            route          = Routes.AVATAR_TEST,
            enterTransition    = { fadeIn(tween(400)) },
            exitTransition     = { fadeOut(tween(300)) },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition  = { fadeOut(tween(400)) },
        ) {
            AvatarTestScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
