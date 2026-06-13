package com.sirilerklab.svcgeyser.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirilerklab.svcgeyser.audio.AudioEngine
import com.sirilerklab.svcgeyser.auth.AuthRepository
import com.sirilerklab.svcgeyser.auth.LiveOAuthHelper
import com.sirilerklab.svcgeyser.auth.XboxAuthHelper
import com.sirilerklab.svcgeyser.auth.XboxSession
import com.sirilerklab.svcgeyser.data.SavedServer
import com.sirilerklab.svcgeyser.data.ServerRepository
import com.sirilerklab.svcgeyser.network.BridgeClient
import com.sirilerklab.svcgeyser.network.GroupInfo
import com.sirilerklab.svcgeyser.network.InboundMessage
import com.sirilerklab.svcgeyser.service.BubbleService
import com.sirilerklab.svcgeyser.service.VoiceService
import com.sirilerklab.svcgeyser.ui.bubble.BubbleController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "SVCGeyser.VM"

sealed class LoginStatus {
    object Idle : LoginStatus()
    object Restoring : LoginStatus()
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
    val authReady: Boolean = false,
    val loginStatus: LoginStatus = LoginStatus.Restoring,
    val connectStatus: ConnectStatus = ConnectStatus.Idle,
    val xboxSession: XboxSession? = null,
    val sessionToken: String? = null,
    val inGame: Boolean = false,
    val javaUuid: String? = null,
    val groups: List<GroupInfo> = emptyList(),
    val currentRoom: String? = null,
    val joinError: String? = null,
    val bubbleEnabled: Boolean = false,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val speakerOn: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    private val authRepo = AuthRepository(ctx)
    private val serverRepo = ServerRepository(ctx)

    val savedServers: StateFlow<List<SavedServer>> = serverRepo.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    private var pendingJoinName: String? = null

    init {
        viewModelScope.launch {
            ui.collect { state ->
                BubbleController.groups.value      = state.groups
                BubbleController.currentRoom.value = state.currentRoom
                BubbleController.inGame.value      = state.inGame
                BubbleController.isMuted.value     = state.isMuted
                BubbleController.isDeafened.value  = state.isDeafened
                BubbleController.speakerOn.value   = state.speakerOn
            }
        }
        BubbleController.onJoin       = { name, pw -> joinRoom(name, pw) }
        BubbleController.onLeave      = { leaveRoom() }
        BubbleController.onToggleMute = { toggleMute() }
        BubbleController.onToggleDeafen = { toggleDeafen() }
        BubbleController.onToggleSpeaker = { toggleSpeaker() }

        restoreSession()
    }

    val isLoggedIn: Boolean get() = _ui.value.xboxSession != null

    // ---- Auth -----------------------------------------------------------

    private fun restoreSession() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loginStatus = LoginStatus.Restoring)
            try {
                val stored = authRepo.load()
                if (stored == null) {
                    _ui.value = _ui.value.copy(loginStatus = LoginStatus.Idle, authReady = true)
                    return@launch
                }
                val msa = LiveOAuthHelper.refreshAccessToken(stored.refreshToken)
                val xboxSession = XboxAuthHelper.exchange(msa.accessToken)
                authRepo.save(msa.refreshToken, xboxSession.xuid, msa.expiresAtMs())
                _ui.value = _ui.value.copy(
                    loginStatus = LoginStatus.Done,
                    xboxSession = xboxSession,
                    authReady = true,
                )
                Log.d(TAG, "restoreSession: success — XUID=${xboxSession.xuid}")
            } catch (e: Exception) {
                Log.w(TAG, "restoreSession: failed — ${e.message}")
                authRepo.clear()
                _ui.value = _ui.value.copy(loginStatus = LoginStatus.Idle, authReady = true)
            }
        }
    }

    fun signIn(activity: Activity) {
        if (_ui.value.loginStatus == LoginStatus.Loading) return
        _ui.value = _ui.value.copy(loginStatus = LoginStatus.Loading)
        viewModelScope.launch {
            try {
                val msa = LiveOAuthHelper.signIn(activity)
                val xboxSession = XboxAuthHelper.exchange(msa.accessToken)
                authRepo.save(msa.refreshToken, xboxSession.xuid, msa.expiresAtMs())
                _ui.value = _ui.value.copy(
                    loginStatus = LoginStatus.Done,
                    xboxSession = xboxSession,
                    authReady = true,
                )
            } catch (e: Exception) {
                Log.e(TAG, "signIn: FAILED — ${e.message}", e)
                _ui.value = _ui.value.copy(
                    loginStatus = LoginStatus.Error(e.message ?: "Sign-in failed"),
                    authReady = true,
                )
            }
        }
    }

    fun signOut() {
        disconnect()
        authRepo.clear()
        _ui.value = AppUiState(authReady = true, loginStatus = LoginStatus.Idle)
    }

    // ---- Server CRUD ----------------------------------------------------

    fun addServer(label: String, host: String, port: Int) {
        viewModelScope.launch { serverRepo.add(label, host, port) }
    }

    fun updateServer(server: SavedServer) {
        viewModelScope.launch { serverRepo.update(server) }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch { serverRepo.delete(id) }
    }

    fun connectToSaved(server: SavedServer) {
        viewModelScope.launch {
            serverRepo.markLastUsed(server.id)
            connect(server.host, server.port)
        }
    }

    // ---- Server connect -------------------------------------------------

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
        connectionGeneration++
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
            isMuted = false,
            isDeafened = false,
            speakerOn = false,
        )
    }

    private fun doConnect(session: XboxSession) {
        val gen = ++connectionGeneration
        messageJob?.cancel()
        bridgeClient?.close()

        _ui.value = _ui.value.copy(connectStatus = ConnectStatus.Connecting)
        val client = BridgeClient().also { bridgeClient = it }
        client.connect(lastAddress ?: return, lastPort, session.authHeader)

        messageJob = viewModelScope.launch {
            for (msg in client.messages) {
                when (msg) {
                    is InboundMessage.AuthOk -> {
                        reconnectDelayMs = 1_000L
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Connected,
                            sessionToken = msg.sessionToken,
                        )
                        VoiceService.startConnected(ctx)
                        client.sendStatus()
                    }
                    is InboundMessage.AuthFail -> {
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Error("Auth failed: ${msg.reason}"),
                        )
                        client.close()
                        VoiceService.stop(ctx)
                    }
                    is InboundMessage.Status -> {
                        _ui.value = _ui.value.copy(
                            inGame = msg.inGame,
                            javaUuid = msg.javaUuid,
                            groups = msg.groups,
                        )
                    }
                    is InboundMessage.PlayerJoinedGame -> {
                        _ui.value = _ui.value.copy(inGame = true, javaUuid = msg.javaUuid)
                        client.sendStatus()
                    }
                    is InboundMessage.PlayerLeftGame -> {
                        _ui.value = _ui.value.copy(
                            inGame = false,
                            javaUuid = null,
                            currentRoom = null,
                        )
                        stopAudioEngine()
                        VoiceService.startConnected(ctx)
                    }
                    is InboundMessage.GroupUpdate -> {
                        val keptRoom = _ui.value.currentRoom?.takeIf { room ->
                            msg.groups.any { it.name == room }
                        }
                        _ui.value = _ui.value.copy(groups = msg.groups, currentRoom = keptRoom)
                    }
                    is InboundMessage.RoomChanged -> {
                        pendingJoinName = null
                        _ui.value = _ui.value.copy(currentRoom = msg.room)
                    }
                    is InboundMessage.JoinOk -> {
                        _ui.value = _ui.value.copy(currentRoom = pendingJoinName)
                        pendingJoinName = null
                    }
                    is InboundMessage.JoinFail -> {
                        _ui.value = _ui.value.copy(joinError = msg.reason)
                        pendingJoinName = null
                    }
                    is InboundMessage.LeaveOk -> {
                        _ui.value = _ui.value.copy(currentRoom = null)
                    }
                    is InboundMessage.DownlinkFrame -> {
                        audioEngine?.handleDownlinkFrame(msg.bytes)
                    }
                    is InboundMessage.Error -> {
                        stopAudioEngine()
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Error(msg.cause.message ?: "Connection error"),
                            currentRoom = null,
                        )
                        if (connectionGeneration == gen) scheduleReconnect()
                    }
                    is InboundMessage.Closed -> {
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
        val state = _ui.value
        audioEngine = AudioEngine(ctx) { frame -> bridgeClient?.sendBinary(frame) }.also { engine ->
            engine.isMuted = state.isMuted
            engine.isDeafened = state.isDeafened
            engine.speakerOn = state.speakerOn
            engine.start()
        }
        VoiceService.startVoice(ctx)
    }

    private fun stopAudioEngine() {
        audioEngine?.stop()
        audioEngine = null
    }

    fun toggleMute() {
        val next = !_ui.value.isMuted
        audioEngine?.isMuted = next
        _ui.value = _ui.value.copy(isMuted = next)
    }

    fun toggleDeafen() {
        val next = !_ui.value.isDeafened
        audioEngine?.isDeafened = next
        _ui.value = _ui.value.copy(isDeafened = next)
    }

    fun toggleSpeaker() {
        val next = !_ui.value.speakerOn
        audioEngine?.applySpeakerRoute(next)
        _ui.value = _ui.value.copy(speakerOn = next)
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

    override fun onCleared() {
        BubbleController.onJoin = null
        BubbleController.onLeave = null
        BubbleController.onToggleMute = null
        BubbleController.onToggleDeafen = null
        BubbleController.onToggleSpeaker = null
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
