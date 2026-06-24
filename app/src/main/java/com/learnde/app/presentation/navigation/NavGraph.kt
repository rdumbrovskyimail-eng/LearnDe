// Путь: app/src/main/java/com/learnde/app/presentation/navigation/NavGraph.kt
package com.learnde.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.learnde.app.presentation.client.ClientScreen
import com.learnde.app.presentation.settings.SettingsScreen

object Routes {
    const val CLIENT = "client"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.CLIENT,
    ) {
        // Главный экран клиента (чат и микрофон)
        composable(
            route = Routes.CLIENT,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) }
        ) {
            ClientScreen(navController = navController)
        }

        // Экран настроек
        composable(
            route = Routes.SETTINGS,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            SettingsScreen(
                onStartSession = {
                    // Возврат на главный экран
                    navController.navigate(Routes.CLIENT) {
                        popUpTo(Routes.CLIENT) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}