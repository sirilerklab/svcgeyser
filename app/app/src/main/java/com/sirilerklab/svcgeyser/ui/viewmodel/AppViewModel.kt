package com.sirilerklab.svcgeyser.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirilerklab.svcgeyser.audio.AudioEngine
import com.sirilerklab.svcgeyser.auth.LiveOAuthHelper
import com.sirilerklab.svcgeyser.auth.XboxAuthHelper
import com.sirilerklab.svcgeyser.auth.XboxSession
import com.sirilerklab.svcgeyser.network.BridgeClient
import com.sirilerklab.svcgeyser.network.GroupInfo
import com.sirilerklab.svcgeyser.network.InboundMessage
import com.sirilerklab.svcgeyser.service.BubbleService
import com.sirilerklab.svcgeyser.service.VoiceService
import com.sirilerklab.svcgeyser.ui.bubble.BubbleController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SVCGeyser.VM"

// ---- UI state -----------------------------------------------------------

sealed class LoginStatus {
    object Idle : LoginStatus()
    object Loading : LoginStatus()
    data class Error(val message: String) : LoginStatus()
    object Done : LoginStatus()
}

sealed class ConnectStatus {
    object Idle : ConnectStatus()
    object Connecting : ConnectStatus()
    data class Error(val message: String) : ConnectStatus()
    object Connected : ConnectStatus()
}

data class AppUiState(
    val loginStatus: LoginStatus = LoginStatus.Idle,
    val connectStatus: ConnectStatus = ConnectStatus.Idle,
    val xboxSession: XboxSession? = null,
    val sessionToken: String? = null,
    val inGame: Boolean = false,
    val javaUuid: String? = null,
    val groups: List<GroupInfo> = emptyList(),
    val currentRoom: String? = null,  // name of the room the user is currently in
    val joinError: String? = null,    // transient; shown as snackbar then cleared
    val bubbleEnabled: Boolean = false,
)

// ---- ViewModel ----------------------------------------------------------

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    private val _ui = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    private var bridgeClient: BridgeClient? = null
    private var messageJob: Job? = null
    private var audioEngine: AudioEngine? = null

    private var lastAddress: String? = null
    private var lastPort: Int = 9000
    private var reconnectDelayMs = 1_000L
    private var reconnectJob: Job? = null
    private var connectionGeneration = 0

    // Tracks which room name we sent in the last join_room request
    // so we can populate currentRoom when join_ok arrives.
    private var pendingJoinName: String? = null

    init {
        // Mirror UI state into BubbleController so BubbleService can observe it.
        viewModelScope.launch {
            ui.collect { state ->
                BubbleController.groups.value      = state.groups
                BubbleController.currentRoom.value = state.currentRoom
                BubbleController.inGame.value      = state.inGame
            }
        }
        BubbleController.onJoin  = { name, pw -> joinRoom(name, pw) }
        BubbleController.onLeave = { leaveRoom() }
    }

    // ---- Login ----------------------------------------------------------

    fun signIn(activity: Activity) {
        if (_ui.value.loginStatus == LoginStatus.Loading) return
        _ui.value = _ui.value.copy(loginStatus = LoginStatus.Loading)
        viewModelScope.launch {
            try {
                Log.d(TAG, "signIn: starting OAuth flow")
                val msaToken = LiveOAuthHelper.signIn(activity)
                Log.d(TAG, "signIn: got MSA token, starting Xbox exchange")
                val xboxSession = XboxAuthHelper.exchange(msaToken)
                Log.d(TAG, "signIn: success — XUID=${xboxSession.xuid}")
                _ui.value = _ui.value.copy(
                    loginStatus = LoginStatus.Done,
                    xboxSession = xboxSession,
                )
            } catch (e: Exception) {
                Log.e(TAG, "signIn: FAILED — ${e.message}", e)
                _ui.value = _ui.value.copy(
                    loginStatus = LoginStatus.Error(e.message ?: "Sign-in failed"),
                )
            }
        }
    }

    // ---- Server connect + auth ------------------------------------------

    fun connect(address: String, port: Int) {
        val session = _ui.value.xboxSession ?: run {
            _ui.value = _ui.value.copy(connectStatus = ConnectStatus.Error("Not signed in"))
            return
        }
        if (_ui.value.connectStatus == ConnectStatus.Connecting) return
        lastAddress = address
        lastPort = port
        reconnectDelayMs = 1_000L
        reconnectJob?.cancel()
        doConnect(session)
    }

    fun disconnect() {
        Log.d(TAG, "disconnect: user requested explicit disconnect")
        connectionGeneration++ // prevent any pending scheduleReconnect from firing
        reconnectJob?.cancel()
        messageJob?.cancel()
        stopAudioEngine()
        bridgeClient?.close()
        bridgeClient = null
        VoiceService.stop(ctx)
        if (_ui.value.bubbleEnabled) BubbleService.stop(ctx)
        _ui.value = _ui.value.copy(
            connectStatus = ConnectStatus.Idle,
            sessionToken = null,
            inGame = false,
            javaUuid = null,
            currentRoom = null,
            joinError = null,
            bubbleEnabled = false,
        )
    }

    private fun doConnect(session: XboxSession) {
        val gen = ++connectionGeneration
        messageJob?.cancel()
        bridgeClient?.close()

        _ui.value = _ui.value.copy(connectStatus = ConnectStatus.Connecting)
        Log.d(TAG, "doConnect: gen=$gen target=${lastAddress}:${lastPort}")

        val client = BridgeClient().also { bridgeClient = it }
        client.connect(lastAddress ?: return, lastPort, session.authHeader)

        messageJob = viewModelScope.launch {
            for (msg in client.messages) {
                when (msg) {
                    is InboundMessage.AuthOk -> {
                        Log.d(TAG, "auth_ok — xuid=${msg.xuid} token=${msg.sessionToken.take(8)}…")
                        reconnectDelayMs = 1_000L // reset backoff on successful auth
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Connected,
                            sessionToken = msg.sessionToken,
                        )
                        VoiceService.startConnected(ctx) // keep process alive in background
                        client.sendStatus()
                    }
                    is InboundMessage.AuthFail -> {
                        Log.w(TAG, "auth_fail — reason=${msg.reason}")
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Error("Auth failed: ${msg.reason}"),
                        )
                        client.close()
                        VoiceService.stop(ctx) // don't reconnect on auth failure
                    }
                    is InboundMessage.Status -> {
                        Log.d(TAG, "status — inGame=${msg.inGame} javaUuid=${msg.javaUuid} groups=${msg.groups.size}")
                        _ui.value = _ui.value.copy(
                            inGame = msg.inGame,
                            javaUuid = msg.javaUuid,
                            groups = msg.groups,
                        )
                    }
                    is InboundMessage.PlayerJoinedGame -> {
                        Log.d(TAG, "player_joined_game — javaUuid=${msg.javaUuid}")
                        _ui.value = _ui.value.copy(inGame = true, javaUuid = msg.javaUuid)
                    }
                    is InboundMessage.PlayerLeftGame -> {
                        Log.d(TAG, "player_left_game — stopping audio")
                        _ui.value = _ui.value.copy(
                            inGame = false,
                            javaUuid = null,
                            currentRoom = null,
                        )
                        stopAudioEngine()
                        VoiceService.startConnected(ctx) // downgrade notification
                    }
                    is InboundMessage.GroupUpdate -> {
                        Log.d(TAG, "group_update — ${msg.groups.size} groups: ${msg.groups.map { it.name }}")
                        _ui.value = _ui.value.copy(groups = msg.groups)
                    }
                    is InboundMessage.JoinOk -> {
                        Log.d(TAG, "join_ok — room=${pendingJoinName}")
                        _ui.value = _ui.value.copy(currentRoom = pendingJoinName)
                        pendingJoinName = null
                    }
                    is InboundMessage.JoinFail -> {
                        Log.w(TAG, "join_fail — reason=${msg.reason}")
                        _ui.value = _ui.value.copy(joinError = msg.reason)
                        pendingJoinName = null
                    }
                    is InboundMessage.LeaveOk -> {
                        Log.d(TAG, "leave_ok")
                        _ui.value = _ui.value.copy(currentRoom = null)
                    }
                    is InboundMessage.DownlinkFrame -> {
                        audioEngine?.handleDownlinkFrame(msg.bytes)
                    }
                    is InboundMessage.Error -> {
                        Log.e(TAG, "connection error — ${msg.cause.message}", msg.cause)
                        stopAudioEngine()
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Error(msg.cause.message ?: "Connection error"),
                            currentRoom = null,
                        )
                        // Keep VoiceService running so process stays alive during reconnect.
                        if (connectionGeneration == gen) scheduleReconnect()
                    }
                    is InboundMessage.Closed -> {
                        Log.d(TAG, "connection closed")
                        stopAudioEngine()
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Idle,
                            inGame = false,
                            javaUuid = null,
                            sessionToken = null,
                            currentRoom = null,
                        )
                        if (connectionGeneration == gen) scheduleReconnect()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun scheduleReconnect() {
        val session = _ui.value.xboxSession ?: return
        Log.d(TAG, "scheduleReconnect: retrying in ${reconnectDelayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            doConnect(session)
        }
    }

    // ---- Audio ----------------------------------------------------------

    fun startAudio() {
        if (audioEngine != null) return
        audioEngine = AudioEngine { frame -> bridgeClient?.sendBinary(frame) }.also { it.start() }
        VoiceService.startVoice(ctx) // upgrade notification to "voice active"
    }

    private fun stopAudioEngine() {
        audioEngine?.stop()
        audioEngine = null
        // Do NOT stop VoiceService here — it keeps the process alive.
        // Caller is responsible for deciding whether to stop or downgrade the service.
    }

    // ---- Room actions ---------------------------------------------------

    fun joinRoom(name: String, password: String?) {
        pendingJoinName = name
        bridgeClient?.sendJoinRoom(name, password)
    }

    fun leaveRoom() {
        bridgeClient?.sendLeaveRoom()
    }

    fun clearJoinError() {
        _ui.value = _ui.value.copy(joinError = null)
    }

    // ---- Bubble ---------------------------------------------------------

    fun toggleBubble() {
        if (_ui.value.bubbleEnabled) {
            BubbleService.stop(ctx)
            _ui.value = _ui.value.copy(bubbleEnabled = false)
        } else {
            BubbleService.start(ctx)
            _ui.value = _ui.value.copy(bubbleEnabled = true)
        }
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onCleared() {
        BubbleController.onJoin  = null
        BubbleController.onLeave = null
        connectionGeneration++
        reconnectJob?.cancel()
        messageJob?.cancel()
        stopAudioEngine()
        bridgeClient?.close()
        bridgeClient = null
        VoiceService.stop(ctx)
        if (_ui.value.bubbleEnabled) BubbleService.stop(ctx)
    }
}
