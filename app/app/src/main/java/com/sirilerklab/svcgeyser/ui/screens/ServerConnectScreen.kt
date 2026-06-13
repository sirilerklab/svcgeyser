package com.sirilerklab.svcgeyser.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sirilerklab.svcgeyser.R
import com.sirilerklab.svcgeyser.data.SavedServer
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel
import com.sirilerklab.svcgeyser.ui.viewmodel.ConnectStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConnectScreen(
    vm: AppViewModel,
    onConnected: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.ui.collectAsState()
    val servers by vm.savedServers.collectAsState()
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9000") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SavedServer?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedServer?>(null) }

    LaunchedEffect(state.connectStatus) {
        if (state.connectStatus == ConnectStatus.Connected) onConnected()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                actions = {
                    TextButton(onClick = onSignOut) {
                        Text(stringResource(R.string.action_sign_out))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add server")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            if (servers.isNotEmpty()) {
                Text(
                    "Saved servers",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(servers, key = { it.id }) { server ->
                        SavedServerRow(
                            server = server,
                            isConnecting = state.connectStatus == ConnectStatus.Connecting,
                            onConnect = { vm.connectToSaved(server) },
                            onEdit = { editTarget = server },
                            onDelete = { deleteTarget = server },
                        )
                    }
                }
            }

            Text(
                "Quick connect",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.server_ip_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text(stringResource(R.string.server_port_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            val connectError = (state.connectStatus as? ConnectStatus.Error)?.message
            if (connectError != null) {
                Text(
                    connectError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.connectStatus == ConnectStatus.Connecting) {
                CircularProgressIndicator(modifier = Modifier
                    .padding(top = 16.dp)
                    .align(Alignment.CenterHorizontally))
            } else {
                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 9000
                        vm.connect(address.trim(), p)
                    },
                    enabled = address.isNotBlank(),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.server_connect))
                }
            }
        }
    }

    if (showAddDialog) {
        ServerFormDialog(
            title = "Add server",
            onDismiss = { showAddDialog = false },
            onConfirm = { label, host, p ->
                vm.addServer(label, host, p)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { server ->
        ServerFormDialog(
            title = "Edit server",
            initialLabel = server.label,
            initialHost = server.host,
            initialPort = server.port.toString(),
            onDismiss = { editTarget = null },
            onConfirm = { label, host, p ->
                vm.updateServer(server.copy(label = label, host = host, port = p))
                editTarget = null
            },
        )
    }

    deleteTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete server?") },
            text = { Text("Remove \"${server.label}\" from saved servers?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteServer(server.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SavedServerRow(
    server: SavedServer,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val lastUsed = if (server.lastUsed > 0) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(server.lastUsed))
    } else null

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isConnecting, onClick = onConnect)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.label, fontWeight = FontWeight.SemiBold)
                Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
                if (lastUsed != null) {
                    Text("Last used: $lastUsed", style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ServerFormDialog(
    title: String,
    initialLabel: String = "",
    initialHost: String = "",
    initialPort: String = "9000",
    onDismiss: () -> Unit,
    onConfirm: (label: String, host: String, port: Int) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 9000
                    onConfirm(label.trim(), host.trim(), p)
                },
                enabled = label.isNotBlank() && host.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
