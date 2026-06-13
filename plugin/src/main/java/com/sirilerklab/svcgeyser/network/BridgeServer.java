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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BridgeServer extends WebSocketServer {

    private static final long IDLE_RESET_MS = 200;

    private final ConcurrentHashMap<WebSocket, AppSession> sessions = new ConcurrentHashMap<>();
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
            case "status"     -> handleStatus(session);
            case "join_room"  -> handleJoinRoom(session, msg);
            case "leave_room" -> handleLeaveRoom(session);
            case "ping"       -> session.send("{\"type\":\"pong\"}");
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        AppSession session = sessions.get(conn);
        if (session == null) return;
        AppSession.State state = session.getState();
        if (state != AppSession.State.IN_GAME && state != AppSession.State.IN_ROOM) return;

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
        if (session.getState() == AppSession.State.CONNECTED) {
            session.send("{\"type\":\"status\",\"inGame\":false,\"groups\":[]}");
            return;
        }
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        UUID javaUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(session.getXuid());
        boolean inGame  = javaUuid != null;
        String uuidPart = inGame ? ",\"javaUuid\":\"" + javaUuid + "\"" : "";
        String groups   = api != null ? GroupManager.toJson(api.getGroups()) : "[]";
        session.send("{\"type\":\"status\",\"inGame\":" + inGame + uuidPart + ",\"groups\":" + groups + "}");
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
            // Create a new group with the provided password.
            String pw = (password != null && !password.isBlank()) ? password : "";
            Group.Builder builder = api.groupBuilder()
                    .setName(name)
                    .setType(Group.Type.NORMAL);
            if (!pw.isEmpty()) builder.setPassword(pw);
            Group created = builder.build();
            gm.track(name, pw);
            connection.setGroup(created);
            log().info("xuid={} created room \"{}\" (password={})", session.getXuid(), name, !pw.isEmpty());
        }

        session.setState(AppSession.State.IN_ROOM);
        session.send("{\"type\":\"join_ok\"}");
    }

    private void handleLeaveRoom(AppSession session) {
        if (session.getState() == AppSession.State.IN_ROOM) {
            leaveGroupIfNeeded(session);
            session.setState(AppSession.State.IN_GAME);
            log().info("xuid={} left room", session.getXuid());
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
                s.send("{\"type\":\"player_left_game\"}");
            }
        }
    }

    /** Called from SVC event thread when any group state changes; push the updated list to all active sessions. */
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
