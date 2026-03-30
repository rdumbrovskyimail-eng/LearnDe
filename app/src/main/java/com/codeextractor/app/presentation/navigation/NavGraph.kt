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
            enterTransition  = { slideInHorizontally(tween(350)) { it } + fadeIn(tween(350)) },
            exitTransition   = { fadeOut(tween(250)) },
            popEnterTransition = { fadeIn(tween(250)) },
            popExitTransition  = { slideOutHorizontally(tween(350)) { it } + fadeOut(tween(350)) },
        ) {
            AvatarTestScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
