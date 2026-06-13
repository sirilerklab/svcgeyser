package com.sirilerklab.svcgeyser.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sirilerklab.svcgeyser.network.GroupInfo
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    vm: AppViewModel,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var passwordTarget by remember { mutableStateOf<GroupInfo?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startAudio() }

    LaunchedEffect(state.inGame) {
        if (state.inGame) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.joinError) {
        state.joinError?.let {
            snackbarHostState.showSnackbar("Failed to join: $it")
            vm.clearJoinError()
        }
    }

    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(ctx)) vm.toggleBubble()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.inGame && state.currentRoom == null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Create channel") },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Rooms")
                        val subtitle = if (state.inGame)
                            "In game · ${state.javaUuid?.take(8) ?: ""}…"
                        else
                            "Waiting for player to join server…"
                        Text(subtitle, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (state.bubbleEnabled) {
                            vm.toggleBubble()
                        } else if (Settings.canDrawOverlays(ctx)) {
                            vm.toggleBubble()
                        } else {
                            overlayPermLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"),
                                ),
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Bubble overlay",
                            tint = if (state.bubbleEnabled) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = {
                        vm.disconnect()
                        onDisconnect()
                    }) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = "Disconnect",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.inGame) {
                AudioControlBar(
                    isMuted = state.isMuted,
                    isDeafened = state.isDeafened,
                    speakerOn = state.speakerOn,
                    onToggleMute = vm::toggleMute,
                    onToggleDeafen = vm::toggleDeafen,
                    onToggleSpeaker = vm::toggleSpeaker,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (state.currentRoom != null) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        ActiveRoomBanner(
                            roomName = state.currentRoom!!,
                            onLeave = { vm.leaveRoom() },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                } else {
                    item { Spacer(Modifier.height(12.dp)) }
                }

                item {
                    Text(
                        "VOICE CHANNELS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                if (state.groups.isEmpty()) {
                    item {
                        Text(
                            "No rooms available yet. Create the first channel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    items(state.groups, key = { it.name }) { group ->
                        VoiceChannelRow(
                            group = group,
                            isCurrentRoom = state.currentRoom == group.name,
                            canJoin = state.inGame && state.currentRoom == null,
                            onJoin = {
                                if (group.hasPassword) passwordTarget = group
                                else vm.joinRoom(group.name, null)
                            },
                            onLeave = { vm.leaveRoom() },
                        )
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    passwordTarget?.let { group ->
        PasswordDialog(
            groupName = group.name,
            onDismiss = { passwordTarget = null },
            onJoin = { pw ->
                vm.joinRoom(group.name, pw.ifBlank { null })
                passwordTarget = null
            },
        )
    }

    if (showCreateDialog) {
        CreateChannelDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, pw ->
                vm.joinRoom(name, pw.ifBlank { null })
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun AudioControlBar(
    isMuted: Boolean,
    isDeafened: Boolean,
    speakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "Mute",
                    tint = if (isMuted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onToggleDeafen) {
                Icon(
                    if (isDeafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                    contentDescription = "Deafen",
                    tint = if (isDeafened) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onToggleSpeaker) {
                Icon(
                    if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.Phone,
                    contentDescription = "Speaker",
                    tint = if (speakerOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActiveRoomBanner(roomName: String, onLeave: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    roomName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Voice connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            TextButton(onClick = onLeave) {
                Text("Leave", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun VoiceChannelRow(
    group: GroupInfo,
    isCurrentRoom: Boolean,
    canJoin: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
) {
    val bg = if (isCurrentRoom)
        MaterialTheme.colorScheme.secondaryContainer
    else
        Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (group.hasPassword) Icons.Filled.Lock else Icons.Filled.VolumeUp,
            contentDescription = null,
            tint = if (isCurrentRoom)
                MaterialTheme.colorScheme.onSecondaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            group.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrentRoom) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrentRoom)
                MaterialTheme.colorScheme.onSecondaryContainer
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isCurrentRoom) {
            TextButton(onClick = onLeave) { Text("Leave") }
        } else {
            TextButton(onClick = onJoin, enabled = canJoin) { Text("Join") }
        }
    }
}

@Composable
private fun PasswordDialog(
    groupName: String,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $groupName") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(onClick = { onJoin(password) }, enabled = password.isNotBlank()) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CreateChannelDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim(), password) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
