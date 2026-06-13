package com.sirilerklab.svcgeyser.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sirilerklab.svcgeyser.ui.screens.LoginScreen
import com.sirilerklab.svcgeyser.ui.screens.RoomListScreen
import com.sirilerklab.svcgeyser.ui.screens.ServerConnectScreen
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel

/**
 * Top-level nav graph. A single [AppViewModel] is scoped to the NavHost so all
 * three screens share authentication + connection state without prop-drilling.
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val vm: AppViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier,
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                vm = vm,
                onSignedIn = { navController.navigate(Screen.ServerConnect.route) },
            )
        }
        composable(Screen.ServerConnect.route) {
            ServerConnectScreen(
                vm = vm,
                onConnected = { navController.navigate(Screen.RoomList.route) },
            )
        }
        composable(Screen.RoomList.route) {
            RoomListScreen(
                vm = vm,
                onDisconnect = { navController.popBackStack() },
            )
        }
    }
}
