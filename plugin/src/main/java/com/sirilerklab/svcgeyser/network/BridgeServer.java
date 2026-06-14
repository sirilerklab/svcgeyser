package com.sirilerklab.svcgeyser.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sirilerklab.svcgeyser.Main;
import com.sirilerklab.svcgeyser.auth.SessionJwt;
import com.sirilerklab.svcgeyser.auth.XboxAuthVerifier;
import com.sirilerklab.svcgeyser.group.GroupManager;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BridgeServer extends WebSocketServer {

    private static final long IDLE_RESET_MS = 200;

    private final ConcurrentHashMap<WebSocket, AppSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean groupUpdateScheduled = false;
    private final ScheduledExecutorService idleChecker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "svcgeyser-idle");
        t.setDaemon(true);
        return t;
    });

    public BridgeServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        idleChecker.scheduleAtFixedRate(this::checkIdle, 100, 100, TimeUnit.MILLISECONDS);
    }

    // ---- WebSocketServer callbacks ---------------------------------------

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new AppSession(conn));
        log().info("App connected from {}", ip(conn));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        AppSession session = sessions.remove(conn);
        if (session != null) {
            leaveGroupIfNeeded(session);
            session.unregisterSender();
            session.unregisterListener();
            String xuid = session.getXuid();
            if (xuid != null) {
                log().info("App disconnected xuid={} ip={} code={}", xuid, ip(conn), code);
            } else {
                log().info("App disconnected (unauthenticated) ip={} code={}", ip(conn), code);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        AppSession session = sessions.get(conn);
        if (session == null) return;
        JsonObject msg;
        try {
            msg = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "auth"       -> handleAuth(session, msg);
            case "status"      -> handleStatus(session);
            case "audio_state" -> handleAudioState(session, msg);
            case "join_room"   -> handleJoinRoom(session, msg);
            case "leave_room"  -> handleLeaveRoom(session);
            case "ping"        -> session.send("{\"type\":\"pong\"}");
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        AppSession session = sessions.get(conn);
        if (session == null) return;
        AppSession.State state = session.getState();
        if (state != AppSession.State.IN_GAME && state != AppSession.State.IN_ROOM) return;

        // Debug log the message size
        // log().info("Uplink frame size: {}", message.remaining());
            
        // Uplink: [u8 type=0x01][u16 seq][opus payload]
        message = message.order(ByteOrder.BIG_ENDIAN);
        if (message.remaining() < 4) return;
        if (message.get() != 0x01) return;
        message.getShort(); // sequence — AudioSender manages its own numbering
        byte[] opus = new byte[message.remaining()];
        message.get(opus);
        session.handleUplinkFrame(opus);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            log().warn("WS error from {}: {}", ip(conn), ex.getMessage());
        } else {
            log().warn("WS server error: {}", ex.getMessage());
        }
    }

    @Override
    public void onStart() {
        log().info("Bridge WS server listening on port {}", getPort());
    }

    // ---- signaling handlers ---------------------------------------------

    private void handleAuth(AppSession session, JsonObject msg) {
        if (session.getState() != AppSession.State.CONNECTED) {
            log().warn("Auth rejected — already authed, state={}", session.getState());
            session.send("{\"type\":\"auth_fail\",\"reason\":\"already_authed\"}");
            return;
        }
        if (!msg.has("xstsHeader")) {
            log().warn("Auth rejected — missing xstsHeader");
            session.send("{\"type\":\"auth_fail\",\"reason\":\"missing_header\"}");
            return;
        }
        String xblHeader = msg.get("xstsHeader").getAsString();
        String xuid = XboxAuthVerifier.verify(xblHeader);
        if (xuid == null) {
            log().warn("Auth rejected — XSTS token invalid or verification failed");
            session.send("{\"type\":\"auth_fail\",\"reason\":\"invalid_token\"}");
            return;
        }

        String token = SessionJwt.issue(xuid, Main.getInstance().getJwtSecret());
        kickOtherSessions(xuid, session.getConnection());
        session.setXuid(xuid);

        UUID javaUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(xuid);
        if (javaUuid != null) {
            log().info("Auth OK — xuid={} already in-game as {}", xuid, javaUuid);
            enterInGame(session, javaUuid);
        } else {
            log().info("Auth OK — xuid={} waiting for player to join server", xuid);
            session.setState(AppSession.State.WAITING_FOR_PLAYER);
        }

        session.send("{\"type\":\"auth_ok\",\"sessionToken\":\"" + token + "\",\"xuid\":\"" + xuid + "\"}");
    }

    private void handleStatus(AppSession session) {
        sendStatusTo(session);
    }

    private void handleAudioState(AppSession session, JsonObject msg) {
        boolean muted    = msg.has("muted")    && msg.get("muted").getAsBoolean();
        boolean deafened = msg.has("deafened") && msg.get("deafened").getAsBoolean();
        session.setMuted(muted);
        session.setDeafened(deafened);
        log().info("xuid={} audio_state muted={} deafened={}", session.getXuid(), muted, deafened);
    }

    private void sendStatusTo(AppSession session) {
        if (session.getState() == AppSession.State.CONNECTED) {
            session.send("{\"type\":\"status\",\"inGame\":false,\"groups\":[]}");
            return;
        }
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        UUID javaUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(session.getXuid());
        boolean inGame  = javaUuid != null;
        String uuidPart = inGame ? ",\"javaUuid\":\"" + javaUuid + "\"" : "";
        String roomPart = "";
        String room = session.getCurrentRoom();
        if (room != null) {
            roomPart = ",\"currentRoom\":\"" + GroupManager.escapeJson(room) + "\"";
        }
        String groups   = api != null ? GroupManager.toJson(api.getGroups()) : "[]";
        session.send("{\"type\":\"status\",\"inGame\":" + inGame + uuidPart + roomPart + ",\"groups\":" + groups + "}");
    }

    private void handleJoinRoom(AppSession session, JsonObject msg) {
        AppSession.State state = session.getState();
        if (state != AppSession.State.IN_GAME && state != AppSession.State.IN_ROOM) {
            session.send("{\"type\":\"join_fail\",\"reason\":\"not_in_game\"}");
            return;
        }
        String name = msg.has("name") ? msg.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            session.send("{\"type\":\"join_fail\",\"reason\":\"invalid_name\"}");
            return;
        }
        String password = msg.has("password") ? msg.get("password").getAsString() : "";
        Group.Type groupType = parseGroupType(msg);
        if (groupType == null) {
            session.send("{\"type\":\"join_fail\",\"reason\":\"invalid_group_type\"}");
            return;
        }

        // All SVC group mutation (create/join/setGroup) and its events must run on the Bukkit
        // main thread — doing it on the WS thread can throw (notably when *creating* a group),
        // and an uncaught exception here would be swallowed by the WS library, leaving the app
        // with no response. The try/catch guarantees the client always gets a reply.
        Main.getInstance().getServer().getScheduler().runTask(Main.getInstance(), () -> {
            try {
                VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
                if (api == null) {
                    session.send("{\"type\":\"join_fail\",\"reason\":\"svc_unavailable\"}");
                    return;
                }

                UUID javaUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(session.getXuid());
                if (javaUuid == null) {
                    session.send("{\"type\":\"join_fail\",\"reason\":\"not_in_game\"}");
                    return;
                }

                VoicechatConnection connection = api.getConnectionOf(javaUuid);
                if (connection == null) {
                    session.send("{\"type\":\"join_fail\",\"reason\":\"no_svc_connection\"}");
                    return;
                }

                GroupManager gm = Main.getInstance().getGroupManager();

                // Find existing group by name.
                Group existing = api.getGroups().stream()
                        .filter(g -> g.getName().equals(name))
                        .findFirst().orElse(null);

                if (existing != null) {
                    if (!gm.checkPassword(existing, password)) {
                        log().warn("xuid={} join_room \"{}\" rejected — wrong password", session.getXuid(), name);
                        session.send("{\"type\":\"join_fail\",\"reason\":\"wrong_password\"}");
                        return;
                    }
                    connection.setGroup(existing);
                    log().info("xuid={} joined room \"{}\"", session.getXuid(), name);
                } else {
                    log().info("xuid={} create room \"{}\" type={} (password={})",
                            session.getXuid(), name, groupType, !password.isEmpty());
                    // Create a new group with the provided password and type.
                    String pw = (password != null && !password.isBlank()) ? password : "";
                    Group.Builder builder = api.groupBuilder()
                            .setName(name)
                            .setType(groupType);
                    if (!pw.isEmpty()) builder.setPassword(pw);
                    Group created = builder.build();
                    gm.track(name, pw);
                    connection.setGroup(created);
                    log().info("xuid={} created room \"{}\" type={} (password={})",
                            session.getXuid(), name, groupType, !pw.isEmpty());
                }

                session.setState(AppSession.State.IN_ROOM);
                session.setCurrentRoom(name);
                session.send("{\"type\":\"join_ok\"}");
                session.send("{\"type\":\"room_changed\",\"room\":\"" + GroupManager.escapeJson(name) + "\"}");
                scheduleBroadcastGroupUpdate();
            } catch (Exception e) {
                log().error("join_room failed for xuid={} room=\"{}\"", session.getXuid(), name, e);
                session.send("{\"type\":\"join_fail\",\"reason\":\"group_error\"}");
            }
        });
    }

    private void handleLeaveRoom(AppSession session) {
        if (session.getState() == AppSession.State.IN_ROOM) {
            leaveGroupIfNeeded(session);
            session.setState(AppSession.State.IN_GAME);
            session.setCurrentRoom(null);
            log().info("xuid={} left room", session.getXuid());
            session.send("{\"type\":\"room_changed\",\"room\":null}");
        }
        session.send("{\"type\":\"leave_ok\"}");
    }

    // ---- Bukkit main-thread callbacks -----------------------------------

    public void notifyPlayerJoined(String xuid, UUID javaUuid) {
        for (AppSession s : sessions.values()) {
            if (xuid.equals(s.getXuid()) && s.getState() == AppSession.State.WAITING_FOR_PLAYER) {
                log().info("xuid={} → in-game (uuid={})", xuid, javaUuid);
                enterInGame(s, javaUuid);
                s.send("{\"type\":\"player_joined_game\",\"javaUuid\":\"" + javaUuid + "\"}");
                sendStatusTo(s);
            }
        }
    }

    public void notifyPlayerQuit(String xuid) {
        for (AppSession s : sessions.values()) {
            AppSession.State st = s.getState();
            if (xuid.equals(s.getXuid()) && (st == AppSession.State.IN_GAME || st == AppSession.State.IN_ROOM)) {
                log().info("xuid={} → waiting (player left server)", xuid);
                // Group is automatically cleared when the player leaves the server;
                // no explicit setGroup(null) needed here.
                s.unregisterSender();
                s.unregisterListener();
                s.setState(AppSession.State.WAITING_FOR_PLAYER);
                s.setCurrentRoom(null);
                s.send("{\"type\":\"player_left_game\"}");
            }
        }
    }

    /**
     * Schedules a group_update broadcast on the next Bukkit tick so api.getGroups()
     * reflects the SVC event that just fired.
     */
    public void scheduleBroadcastGroupUpdate() {
        if (groupUpdateScheduled) return;
        groupUpdateScheduled = true;
        Main.getInstance().getServer().getScheduler().runTaskLater(
                Main.getInstance(), () -> {
                    groupUpdateScheduled = false;
                    broadcastGroupUpdate();
                }, 1L);
    }

    /** Push the updated group list to all active sessions. */
    public void broadcastGroupUpdate() {
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api == null) return;
        String json = "{\"type\":\"group_update\",\"groups\":" + GroupManager.toJson(api.getGroups()) + "}";
        for (AppSession s : sessions.values()) {
            AppSession.State st = s.getState();
            if (st == AppSession.State.IN_GAME || st == AppSession.State.IN_ROOM
                    || st == AppSession.State.WAITING_FOR_PLAYER) {
                s.send(json);
            }
        }
    }

    /** Notify the app session for a bridged player that their room membership changed. */
    public void notifyRoomChanged(UUID javaUuid, String roomName) {
        for (AppSession s : sessions.values()) {
            String xuid = s.getXuid();
            if (xuid == null) continue;
            UUID sessionUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(xuid);
            if (!javaUuid.equals(sessionUuid)) continue;
            AppSession.State st = s.getState();
            if (st != AppSession.State.IN_GAME && st != AppSession.State.IN_ROOM) continue;

            if (roomName != null) {
                s.setState(AppSession.State.IN_ROOM);
                s.setCurrentRoom(roomName);
                s.send("{\"type\":\"room_changed\",\"room\":\"" + GroupManager.escapeJson(roomName) + "\"}");
                log().info("xuid={} room_changed → \"{}\"", xuid, roomName);
            } else {
                s.setState(AppSession.State.IN_GAME);
                s.setCurrentRoom(null);
                s.send("{\"type\":\"room_changed\",\"room\":null}");
                log().info("xuid={} room_changed → null", xuid);
            }
            return;
        }
    }

    /** Called when SVC removes a group; clear affected app sessions. */
    public void notifyGroupRemoved(String groupName) {
        for (AppSession s : sessions.values()) {
            if (!groupName.equals(s.getCurrentRoom())) continue;
            s.setState(AppSession.State.IN_GAME);
            s.setCurrentRoom(null);
            s.send("{\"type\":\"room_changed\",\"room\":null}");
            log().info("xuid={} room_changed → null (group \"{}\" removed)", s.getXuid(), groupName);
        }
    }

    // ---- helpers --------------------------------------------------------

    private void enterInGame(AppSession session, UUID javaUuid) {
        session.setState(AppSession.State.IN_GAME);
        session.registerListener(javaUuid);
        if (!session.registerSender(javaUuid)) {
            // SVC connection not ready yet — retry once on the Bukkit main thread after 20 ticks.
            Main.getInstance().getServer().getScheduler().runTaskLater(
                    Main.getInstance(), () -> {
                        AppSession.State st = session.getState();
                        if (st == AppSession.State.IN_GAME || st == AppSession.State.IN_ROOM) {
                            log().info("Retrying registerSender for uuid={}", javaUuid);
                            session.registerSender(javaUuid);
                        }
                    }, 20L);
        }
    }

    /**
     * Returns the AppSession bridging the given in-game player, or null if that player is
     * not an app-bridged (Bedrock) player. Used to resolve a sender's authoritative room
     * state from our own volatile fields instead of SVC's (possibly lagging) API.
     */
    public AppSession sessionForPlayer(UUID javaUuid) {
        for (AppSession s : sessions.values()) {
            String x = s.getXuid();
            if (x == null) continue;
            UUID u = Main.getInstance().getXuidPlayerMap().getJavaUuid(x);
            if (javaUuid.equals(u)) return s;
        }
        return null;
    }

    /** All active app sessions (one per WebSocket connection). */
    public Collection<AppSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /** Find an app session by XUID, or null if not connected. */
    public AppSession findSessionByXuid(String xuid) {
        if (xuid == null) return null;
        for (AppSession s : sessions.values()) {
            if (xuid.equals(s.getXuid())) return s;
        }
        return null;
    }

    /** Close any other open WebSocket for the same XUID (reconnect dedup). */
    private void kickOtherSessions(String xuid, WebSocket keepConn) {
        for (var entry : sessions.entrySet()) {
            WebSocket ws = entry.getKey();
            AppSession s = entry.getValue();
            if (ws != keepConn && xuid.equals(s.getXuid())) {
                log().info("Closing duplicate app session for xuid={}", xuid);
                ws.close(1000, "replaced by new connection");
            }
        }
    }

    /** Parse join_room groupType wire value; defaults to ISOLATED when omitted. */
    private static Group.Type parseGroupType(JsonObject msg) {
        if (!msg.has("groupType")) return Group.Type.ISOLATED;
        return switch (msg.get("groupType").getAsString().toLowerCase()) {
            case "normal"   -> Group.Type.NORMAL;
            case "open"     -> Group.Type.OPEN;
            case "isolated" -> Group.Type.ISOLATED;
            default         -> null;
        };
    }

    /** Clears the SVC group for the app session's player if they are currently in a room. */
    private void leaveGroupIfNeeded(AppSession session) {
        if (session.getState() != AppSession.State.IN_ROOM) return;
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        String xuid = session.getXuid();
        if (api == null || xuid == null) return;
        UUID javaUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(xuid);
        if (javaUuid == null) return;
        VoicechatConnection connection = api.getConnectionOf(javaUuid);
        if (connection != null) connection.setGroup(null);
    }

    private void checkIdle() {
        long now = System.currentTimeMillis();
        for (AppSession s : sessions.values()) {
            AppSession.State st = s.getState();
            if (st == AppSession.State.IN_GAME || st == AppSession.State.IN_ROOM) {
                s.resetSenderIfIdle(now, IDLE_RESET_MS);
            }
        }
    }

    public void closeAll() {
        idleChecker.shutdown();
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- helpers --------------------------------------------------------

    private static org.slf4j.Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }

    private static String ip(WebSocket conn) {
        var addr = conn.getRemoteSocketAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }
}
