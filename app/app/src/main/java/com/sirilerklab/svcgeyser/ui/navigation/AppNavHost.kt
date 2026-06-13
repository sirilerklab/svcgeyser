package com.sirilerklab.svcgeyser.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sirilerklab.svcgeyser.ui.screens.LoginScreen
import com.sirilerklab.svcgeyser.ui.screens.RoomListScreen
import com.sirilerklab.svcgeyser.ui.screens.ServerConnectScreen
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel
import com.sirilerklab.svcgeyser.ui.viewmodel.LoginStatus

@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val vm: AppViewModel = viewModel()
    val state by vm.ui.collectAsState()

    if (!state.authReady) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startRoute = if (vm.isLoggedIn) Screen.ServerConnect.route else Screen.Login.route

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                vm = vm,
                onSignedIn = {
                    navController.navigate(Screen.ServerConnect.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
            )
        }
        composable(Screen.ServerConnect.route) {
            ServerConnectScreen(
                vm = vm,
                onConnected = {
                    navController.navigate(Screen.RoomList.route) {
                        popUpTo(Screen.ServerConnect.route) { inclusive = true }
                    }
                },
                onSignOut = {
                    vm.signOut()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Screen.RoomList.route) {
            RoomListScreen(
                vm = vm,
                onDisconnect = {
                    navController.navigate(Screen.ServerConnect.route) {
                        popUpTo(Screen.RoomList.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
