package io.linkmate.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.linkmate.ui.screens.HomeScreen
import io.linkmate.ui.screens.SettingsScreen
import io.linkmate.ui.screens.EntitySelectionScreen
import io.linkmate.ui.viewmodels.HomeViewModel

object Screen {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ENTITY_SELECTION = "entity_selection"
}

@Composable
fun AppNavGraph(
    navController: NavHostController
) {
    NavHost(navController = navController, startDestination = Screen.HOME) {
        composable(Screen.HOME) {
            val homeViewModel: HomeViewModel = hiltViewModel()
            HomeScreen(navController = navController, viewModel = homeViewModel)
        }
        composable(Screen.SETTINGS) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.ENTITY_SELECTION) {
            EntitySelectionScreen(navController = navController)
        }
    }
}