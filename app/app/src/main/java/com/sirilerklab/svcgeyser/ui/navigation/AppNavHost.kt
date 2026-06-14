package com.sirilerklab.svcgeyser.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sirilerklab.svcgeyser.ui.screens.LoginScreen
import com.sirilerklab.svcgeyser.ui.screens.RoomListScreen
import com.sirilerklab.svcgeyser.ui.screens.ServerConnectScreen
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel
import com.sirilerklab.svcgeyser.ui.viewmodel.CrashUploadStatus
import com.sirilerklab.svcgeyser.ui.viewmodel.LoginStatus

@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val vm: AppViewModel = viewModel()
    val state by vm.ui.collectAsState()

    state.lastCrash?.let { crash ->
        CrashReportDialog(
            report = crash,
            uploadConfigured = state.crashUploadConfigured,
            uploadStatus = state.crashUpload,
            onSend = vm::sendCrashReport,
            onDismiss = vm::clearLastCrash,
        )
    }

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

/** Shows the previous crash's stack trace with an option to upload it to the crash-log service. */
@Composable
private fun CrashReportDialog(
    report: String,
    uploadConfigured: Boolean,
    uploadStatus: CrashUploadStatus,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App crashed last time") },
        text = {
            Column {
                when (uploadStatus) {
                    is CrashUploadStatus.Sending ->
                        Text("Sending crash report…")
                    is CrashUploadStatus.Sent ->
                        Text("Report sent ✓\n${uploadStatus.url}")
                    is CrashUploadStatus.Failed ->
                        Text("Upload failed: ${uploadStatus.message}")
                    CrashUploadStatus.Idle ->
                        if (!uploadConfigured) Text("Crash uploading is not configured in this build.")
                }
                Text(
                    text = report,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            val sent = uploadStatus is CrashUploadStatus.Sent
            val sending = uploadStatus is CrashUploadStatus.Sending
            if (uploadConfigured && !sent) {
                TextButton(onClick = onSend, enabled = !sending) {
                    Text(if (uploadStatus is CrashUploadStatus.Failed) "Retry" else "Send report")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}
