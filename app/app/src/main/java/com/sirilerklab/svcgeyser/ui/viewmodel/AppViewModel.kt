package com.sirilerklab.svcgeyser.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.provider.Settings
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
import com.sirilerklab.svcgeyser.network.GroupType
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
    data class Reconnecting(val attemptDelayMs: Long) : ConnectStatus()
    data class Error(val message: String) : ConnectStatus()
    object Connected : ConnectStatus()
}

val ConnectStatus.isOnline: Boolean
    get() = this is ConnectStatus.Connected

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
    val showReconnected: Boolean = false,
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
    private var statusPollJob: Job? = null
    private var audioEngine: AudioEngine? = null

    private var lastAddress: String? = null
    private var lastPort: Int = 9000
    private var reconnectDelayMs = 1_000L
    private var reconnectJob: Job? = null
    private var connectionGeneration = 0
    private var pendingJoinName: String? = null
    private var lastRoomPassword: String? = null
    private var savedRoom: String? = null
    private var savedRoomPassword: String? = null
    private var wasAudioActive = false
    private var awaitingReconnect = false

    init {
        viewModelScope.launch {
            ui.collect { state ->
                BubbleController.groups.value      = state.groups
                BubbleController.currentRoom.value = state.currentRoom
                BubbleController.inGame.value      = state.inGame
                BubbleController.isMuted.value     = state.isMuted
                BubbleController.isDeafened.value  = state.isDeafened
                BubbleController.speakerOn.value   = state.speakerOn
                BubbleController.joinError.value   = state.joinError
            }
        }
        BubbleController.onJoin           = { name, pw -> joinRoom(name, pw) }
        BubbleController.onCreateChannel  = { name, pw, type -> joinRoom(name, pw, type) }
        BubbleController.onLeave          = { leaveRoom() }
        BubbleController.onToggleMute = { toggleMute() }
        BubbleController.onToggleDeafen = { toggleDeafen() }
        BubbleController.onToggleSpeaker = { toggleSpeaker() }
        BubbleController.onClearJoinError = { clearJoinError() }

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
        awaitingReconnect = false
        savedRoom = null
        savedRoomPassword = null
        wasAudioActive = false
        reconnectJob?.cancel()
        doConnect(session)
    }

    fun disconnect() {
        connectionGeneration++
        reconnectJob?.cancel()
        awaitingReconnect = false
        savedRoom = null
        savedRoomPassword = null
        wasAudioActive = false
        statusPollJob?.cancel()
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
            showReconnected = false,
        )
    }

    private fun handleTransientDisconnect(gen: Int) {
        wasAudioActive = audioEngine != null
        stopAudioEngine()
        savedRoom = _ui.value.currentRoom ?: savedRoom
        savedRoomPassword = lastRoomPassword
        awaitingReconnect = true
        VoiceService.updateConnectionState(ctx, reconnecting = true, voiceActive = false)
        _ui.value = _ui.value.copy(
            connectStatus = ConnectStatus.Reconnecting(reconnectDelayMs),
            sessionToken = null,
            currentRoom = null,
        )
        if (connectionGeneration == gen) scheduleReconnect()
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
                        val wasReconnect = awaitingReconnect
                        awaitingReconnect = false
                        reconnectDelayMs = 1_000L
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Connected,
                            sessionToken = msg.sessionToken,
                            showReconnected = wasReconnect,
                        )
                        VoiceService.updateConnectionState(ctx, reconnecting = false, voiceActive = wasAudioActive)
                        client.sendStatus()
                        startStatusPolling(client)
                        savedRoom?.let { room ->
                            joinRoom(room, savedRoomPassword, null)
                        }
                    }
                    is InboundMessage.AuthFail -> {
                        _ui.value = _ui.value.copy(
                            connectStatus = ConnectStatus.Error("Auth failed: ${msg.reason}"),
                        )
                        client.close()
                        VoiceService.stop(ctx)
                    }
                    is InboundMessage.Status -> {
                        val wasInGame = _ui.value.inGame
                        _ui.value = _ui.value.copy(
                            inGame = msg.inGame,
                            javaUuid = msg.javaUuid,
                            groups = msg.groups,
                            currentRoom = msg.currentRoom ?: _ui.value.currentRoom,
                        )
                        if (msg.inGame && !wasInGame) maybeAutoStartBubble()
                        if (wasAudioActive && msg.inGame && audioEngine == null) {
                            wasAudioActive = false
                            startAudio()
                        }
                    }
                    is InboundMessage.PlayerJoinedGame -> {
                        _ui.value = _ui.value.copy(inGame = true, javaUuid = msg.javaUuid)
                        client.sendStatus()
                        maybeAutoStartBubble()
                    }
                    is InboundMessage.PlayerLeftGame -> {
                        _ui.value = _ui.value.copy(
                            inGame = false,
                            javaUuid = null,
                            currentRoom = null,
                        )
                        stopAudioEngine()
                        VoiceService.updateConnectionState(ctx, reconnecting = false, voiceActive = false)
                    }
                    is InboundMessage.GroupUpdate -> {
                        _ui.value = _ui.value.copy(groups = msg.groups)
                    }
                    is InboundMessage.RoomChanged -> {
                        pendingJoinName = null
                        _ui.value = _ui.value.copy(currentRoom = msg.room)
                    }
                    is InboundMessage.JoinOk -> {
                        pendingJoinName?.let { _ui.value = _ui.value.copy(currentRoom = it) }
                        pendingJoinName = null
                        savedRoom = null
                        savedRoomPassword = null
                    }
                    is InboundMessage.JoinFail -> {
                        _ui.value = _ui.value.copy(joinError = joinFailMessage(msg.reason))
                        pendingJoinName = null
                    }
                    is InboundMessage.LeaveOk -> {
                        _ui.value = _ui.value.copy(currentRoom = null)
                    }
                    is InboundMessage.DownlinkFrame -> {
                        audioEngine?.handleDownlinkFrame(msg.bytes)
                    }
                    is InboundMessage.Error -> handleTransientDisconnect(gen)
                    is InboundMessage.Closed -> handleTransientDisconnect(gen)
                    else -> Unit
                }
            }
        }
    }

    private fun startStatusPolling(client: BridgeClient) {
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                client.sendStatus()
            }
        }
    }

    private fun scheduleReconnect() {
        val session = _ui.value.xboxSession ?: return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(connectStatus = ConnectStatus.Reconnecting(reconnectDelayMs))
            VoiceService.updateConnectionState(ctx, reconnecting = true, voiceActive = false)
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
        bridgeClient?.sendAudioState(state.isMuted, state.isDeafened)
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
        bridgeClient?.sendAudioState(next, _ui.value.isDeafened)
    }

    fun toggleDeafen() {
        val next = !_ui.value.isDeafened
        audioEngine?.isDeafened = next
        _ui.value = _ui.value.copy(isDeafened = next)
        bridgeClient?.sendAudioState(_ui.value.isMuted, next)
    }

    fun toggleSpeaker() {
        val next = !_ui.value.speakerOn
        audioEngine?.applySpeakerRoute(next)
        _ui.value = _ui.value.copy(speakerOn = next)
    }

    // ---- Room actions ---------------------------------------------------

    fun joinRoom(name: String, password: String?, groupType: GroupType? = null) {
        pendingJoinName = name
        if (!password.isNullOrBlank()) lastRoomPassword = password
        bridgeClient?.sendJoinRoom(name, password, groupType)
    }

    fun leaveRoom() {
        bridgeClient?.sendLeaveRoom()
        lastRoomPassword = null
    }

    fun clearJoinError() {
        _ui.value = _ui.value.copy(joinError = null)
    }

    /** Maps a server join_fail reason code to a user-facing message. */
    private fun joinFailMessage(reason: String): String = when (reason) {
        "wrong_password"     -> "Incorrect password"
        "invalid_name"       -> "Invalid channel name"
        "invalid_group_type" -> "Invalid channel type"
        "not_in_game"        -> "You must be on the server to join a channel"
        "no_svc_connection"  -> "Voice chat is not ready yet — try again"
        "svc_unavailable"    -> "Voice chat is unavailable on this server"
        "group_error"        -> "Couldn't create the channel — try again"
        else                  -> "Couldn't join the channel ($reason)"
    }

    fun clearReconnected() {
        _ui.value = _ui.value.copy(showReconnected = false)
    }

    // ---- Bubble ---------------------------------------------------------

    private fun maybeAutoStartBubble() {
        if (_ui.value.bubbleEnabled || !_ui.value.inGame) return
        if (!Settings.canDrawOverlays(ctx)) return
        BubbleService.start(ctx)
        _ui.value = _ui.value.copy(bubbleEnabled = true)
    }

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
        BubbleController.onCreateChannel = null
        BubbleController.onLeave = null
        BubbleController.onToggleMute = null
        BubbleController.onToggleDeafen = null
        BubbleController.onToggleSpeaker = null
        BubbleController.onClearJoinError = null
        connectionGeneration++
        reconnectJob?.cancel()
        statusPollJob?.cancel()
        messageJob?.cancel()
        stopAudioEngine()
        bridgeClient?.close()
        bridgeClient = null
        VoiceService.stop(ctx)
        if (_ui.value.bubbleEnabled) BubbleService.stop(ctx)
    }
}
