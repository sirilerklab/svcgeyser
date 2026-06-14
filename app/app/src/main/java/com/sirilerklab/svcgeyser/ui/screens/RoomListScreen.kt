package com.sirilerklab.svcgeyser.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import com.sirilerklab.svcgeyser.network.GroupType
import com.sirilerklab.svcgeyser.network.RoomMember
import com.sirilerklab.svcgeyser.ui.viewmodel.AppViewModel
import com.sirilerklab.svcgeyser.ui.viewmodel.ConnectStatus
import com.sirilerklab.svcgeyser.ui.viewmodel.isOnline

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

    LaunchedEffect(state.showReconnected) {
        if (state.showReconnected) {
            snackbarHostState.showSnackbar("Reconnected")
            vm.clearReconnected()
        }
    }

    val isOnline = state.connectStatus.isOnline

    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(ctx)) vm.toggleBubble()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.inGame && state.currentRoom == null && isOnline) {
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
            ConnectionBanner(connectStatus = state.connectStatus)

            if (state.inGame && isOnline) {
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
                            onLeave = { if (isOnline) vm.leaveRoom() },
                        )
                        Spacer(Modifier.height(12.dp))
                        RoomMembersSection(
                            members = state.roomMembers,
                            speakingUuids = state.speakingUuids,
                            selfUuid = state.javaUuid,
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
                            canJoin = state.inGame && state.currentRoom == null && isOnline,
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
            onCreate = { name, pw, type ->
                vm.joinRoom(name, pw.ifBlank { null }, type)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun ConnectionBanner(connectStatus: ConnectStatus) {
    when (connectStatus) {
        is ConnectStatus.Reconnecting -> {
            val seconds = (connectStatus.attemptDelayMs / 1000).coerceAtLeast(1)
            Surface(
                color = Color(0xFFFFF3E0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Connection lost — reconnecting in ${seconds}s…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE65100),
                )
            }
        }
        ConnectStatus.Connecting -> {
            Surface(
                color = Color(0xFFFFF3E0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Reconnecting…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE65100),
                )
            }
        }
        else -> Unit
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
                    imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (isMuted) "Unmute microphone" else "Mute microphone",
                    tint = if (isMuted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onToggleDeafen) {
                Icon(
                    imageVector = if (isDeafened) Icons.Filled.HearingDisabled else Icons.Filled.Hearing,
                    contentDescription = if (isDeafened) "Undeafen" else "Deafen",
                    tint = if (isDeafened) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onToggleSpeaker) {
                Icon(
                    imageVector = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Headphones,
                    contentDescription = if (speakerOn) "Switch to headphones" else "Switch to speaker",
                    tint = if (speakerOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RoomMembersSection(
    members: List<RoomMember>,
    speakingUuids: Set<String>,
    selfUuid: String?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "MEMBERS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (members.isEmpty()) {
            Text(
                "No other members in channel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            members.forEach { member ->
                val isSpeaking = member.uuid in speakingUuids
                val isSelf = member.uuid == selfUuid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSpeaking) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isSelf) "${member.name} (you)" else member.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSpeaking) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSpeaking) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSpeaking) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Speaking",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
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
            imageVector = if (group.hasPassword) Icons.Filled.Lock else Icons.AutoMirrored.Filled.VolumeUp,
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
        Text(
            group.type.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
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
    onCreate: (name: String, password: String, groupType: GroupType) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(GroupType.ISOLATED) }

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
                Text(
                    "Group type",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                GroupType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedType = type }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            colors = RadioButtonDefaults.colors(),
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(type.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                type.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), password, selectedType) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
