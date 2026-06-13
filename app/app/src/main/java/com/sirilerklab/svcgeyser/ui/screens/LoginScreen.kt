package com.sirilerklab.svcgeyser.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sirilerklab.svcgeyser.R
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel
import com.sirilerklab.svcgeyser.ui.viewmodel.LoginStatus

@Composable
fun LoginScreen(
    vm: AppViewModel,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.loginStatus) {
        if (state.loginStatus == LoginStatus.Done) onSignedIn()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        when (val status = state.loginStatus) {
            is LoginStatus.Restoring, is LoginStatus.Loading ->
                CircularProgressIndicator()
            is LoginStatus.Error -> {
                Text(
                    text = status.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(onClick = { vm.signIn(context as android.app.Activity) }) {
                    Text(stringResource(R.string.login_sign_in))
                }
            }
            else -> {
                Button(onClick = { vm.signIn(context as android.app.Activity) }) {
                    Text(stringResource(R.string.login_sign_in))
                }
            }
        }
    }
}
