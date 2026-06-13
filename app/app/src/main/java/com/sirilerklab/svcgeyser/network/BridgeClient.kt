package com.sirilerklab.svcgeyser.network

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "SVCGeyser.WS"

sealed class InboundMessage {
    data class AuthOk(val sessionToken: String, val xuid: String) : InboundMessage()
    data class AuthFail(val reason: String) : InboundMessage()
    data class Status(val inGame: Boolean, val javaUuid: String?, val groups: List<GroupInfo>) : InboundMessage()
    data class PlayerJoinedGame(val javaUuid: String) : InboundMessage()
    object PlayerLeftGame : InboundMessage()
    data class GroupUpdate(val groups: List<GroupInfo>) : InboundMessage()
    object JoinOk : InboundMessage()
    data class JoinFail(val reason: String) : InboundMessage()
    object LeaveOk : InboundMessage()
    object Pong : InboundMessage()
    data class DownlinkFrame(val bytes: ByteArray) : InboundMessage()
    data class Error(val cause: Throwable) : InboundMessage()
    object Closed : InboundMessage()
}

data class GroupInfo(val name: String, val hasPassword: Boolean)

class BridgeClient {

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    val messages: Channel<InboundMessage> = Channel(64)

    private val socket = AtomicReference<WebSocket?>(null)

    fun connect(address: String, port: Int, authHeader: String) {
        val url = "ws://$address:$port"
        Log.d(TAG, "Connecting to $url")
        val request = Request.Builder().url(url).build()
        http.newWebSocket(request, Listener(authHeader))
    }

    fun sendStatus() {
        Log.d(TAG, "→ status")
        send("""{"type":"status"}""")
    }

    fun sendPing() = send("""{"type":"ping"}""")

    fun sendJoinRoom(name: String, password: String?) {
        Log.d(TAG, "→ join_room name=$name hasPassword=${password != null}")
        val pw = if (password.isNullOrBlank()) "" else ""","password":"${password.replace("\"", "\\\"")}""""
        send("""{"type":"join_room","name":"${name.replace("\"", "\\\"")}"$pw}""")
    }

    fun sendLeaveRoom() {
        Log.d(TAG, "→ leave_room")
        send("""{"type":"leave_room"}""")
    }

    fun sendBinary(frame: ByteArray) {
        socket.get()?.send(ByteString.of(*frame))
    }

    fun close() {
        Log.d(TAG, "Closing WebSocket (client request)")
        socket.getAndSet(null)?.close(1000, "client disconnect")
    }

    private fun send(json: String) {
        if (socket.get() == null) Log.w(TAG, "send() called but socket is null — message dropped: $json")
        socket.get()?.send(json)
    }

    private inner class Listener(private val authHeader: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened (HTTP ${response.code}) — sending auth")
            socket.set(webSocket)
            webSocket.send("""{"type":"auth","xstsHeader":"${authHeader.replace("\"", "\\\"")}"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                parse(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message: $text", e)
                return
            }
            // Log every inbound signaling message (skip audio frames — too noisy).
            Log.d(TAG, "← $text")
            messages.trySendBlocking(msg)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Audio downlink — log only the first byte (frame type) to avoid spam.
            Log.v(TAG, "← binary frame ${bytes.size} bytes (type=0x${bytes[0].toInt().and(0xFF).toString(16)})")
            messages.trySendBlocking(InboundMessage.DownlinkFrame(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpInfo = response?.let { " (HTTP ${it.code})" } ?: ""
            Log.e(TAG, "WebSocket failure$httpInfo: ${t.message}", t)
            socket.set(null)
            messages.trySendBlocking(InboundMessage.Error(t))
            messages.close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed — code=$code reason=$reason")
            socket.set(null)
            messages.trySendBlocking(InboundMessage.Closed)
            messages.close()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing — code=$code reason=$reason")
        }
    }

    // ---- JSON parser ----------------------------------------------------

    private fun parse(text: String): InboundMessage {
        val j = JSONObject(text)
        return when (j.getString("type")) {
            "auth_ok"          -> InboundMessage.AuthOk(
                sessionToken = j.getString("sessionToken"),
                xuid         = j.getString("xuid"),
            )
            "auth_fail"        -> InboundMessage.AuthFail(j.optString("reason", "unknown"))
            "status"           -> InboundMessage.Status(
                inGame   = j.getBoolean("inGame"),
                javaUuid = if (j.has("javaUuid")) j.getString("javaUuid") else null,
                groups   = parseGroups(j),
            )
            "player_joined_game" -> InboundMessage.PlayerJoinedGame(j.getString("javaUuid"))
            "player_left_game"   -> InboundMessage.PlayerLeftGame
            "group_update"       -> InboundMessage.GroupUpdate(parseGroups(j))
            "join_ok"            -> InboundMessage.JoinOk
            "join_fail"          -> InboundMessage.JoinFail(j.optString("reason", "unknown"))
            "leave_ok"           -> InboundMessage.LeaveOk
            "pong"               -> InboundMessage.Pong
            else -> {
                Log.w(TAG, "Unknown message type: ${j.optString("type")}")
                InboundMessage.Pong
            }
        }
    }

    private fun parseGroups(j: JSONObject): List<GroupInfo> {
        val arr = j.optJSONArray("groups") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            GroupInfo(name = g.getString("name"), hasPassword = g.getBoolean("hasPassword"))
        }
    }
}
