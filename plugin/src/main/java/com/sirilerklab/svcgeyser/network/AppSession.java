package com.sirilerklab.svcgeyser.network;

import com.sirilerklab.svcgeyser.Main;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import org.java_websocket.WebSocket;

import java.util.UUID;

public class AppSession {

    public enum State {
        CONNECTED, AUTHED, WAITING_FOR_PLAYER, IN_GAME, IN_ROOM
    }

    private final WebSocket conn;
    private volatile State  state = State.CONNECTED;
    private volatile String xuid;
    private volatile String currentRoom;
    private volatile boolean muted;
    private volatile boolean deafened;

    // downlink
    private volatile PlayerAudioListener audioListener;

    // uplink
    private volatile AudioSender         audioSender;
    private volatile VoicechatConnection vcConnection;   // stored for setConnected(false) on cleanup
    private volatile long                lastUplinkMs;

    public AppSession(WebSocket conn) {
        this.conn = conn;
    }

    public State  getState()            { return state; }
    public void   setState(State state) { this.state = state; }
    public String getXuid()                  { return xuid; }
    public void   setXuid(String xuid)       { this.xuid = xuid; }
    public String getCurrentRoom()           { return currentRoom; }
    public void   setCurrentRoom(String room) { this.currentRoom = room; }
    public boolean isMuted()                 { return muted; }
    public void    setMuted(boolean muted)   { this.muted = muted; }
    public boolean isDeafened()              { return deafened; }
    public void    setDeafened(boolean deafened) { this.deafened = deafened; }

    public WebSocket getConnection()           { return conn; }
    public boolean hasAudioSender()            { return audioSender != null; }
    public boolean hasAudioListener()          { return audioListener != null; }

    public void send(String json) {
        if (conn.isOpen()) conn.send(json);
    }

    public void sendBinary(byte[] frame) {
        if (conn.isOpen()) conn.send(frame);
    }

    // ---- downlink -------------------------------------------------------

    public void registerListener(UUID playerUuid) {
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api == null) return;
        audioListener = api.playerAudioListenerBuilder()
                .setPlayer(playerUuid)
                .setPacketListener(packet -> {
                    // PlayerAudioListener fires before SVC's client-side group filter.
                    // Enforce group isolation using currentRoom (volatile — reflects setGroup
                    // immediately on all threads, avoiding any API-level caching lag).
                    if (!groupAllows(api, packet.getSender())) return;
                    sendBinary(AudioFrameSerializer.serialize(packet));
                })
                .build();
        api.registerAudioListener(audioListener);
        log().info("Audio listener registered — xuid={} uuid={}", xuid, playerUuid);
    }

    /**
     * Returns true if a packet from {@code senderUuid} should be forwarded to this session.
     * Rules (mirrors what the SVC mod does client-side):
     *   - Neither in a group  → allow (normal proximity)
     *   - Only one is in a group → block (groups are isolated from proximity)
     *   - Both in groups       → allow only if same group
     */
    private boolean groupAllows(VoicechatServerApi api, UUID senderUuid) {
        String myRoom     = currentRoom;                       // volatile read — authoritative
        String senderRoom = resolveSenderRoom(api, senderUuid);

        if (myRoom == null && senderRoom == null) return true;   // both proximity → allow
        if (myRoom == null || senderRoom == null) return false;  // one in group, other not → block
        return myRoom.equals(senderRoom);                        // both in groups → same group only
    }

    /**
     * Resolves the sender's current room. For app-bridged (Bedrock) senders we read their
     * AppSession's volatile {@code currentRoom} directly, so the check reflects join/leave
     * immediately. For Java SVC-mod senders we fall back to the SVC connection's group.
     */
    private static String resolveSenderRoom(VoicechatServerApi api, UUID senderUuid) {
        if (senderUuid == null) return null;
        AppSession senderSession = Main.getInstance().getBridgeServer().sessionForPlayer(senderUuid);
        if (senderSession != null) return senderSession.getCurrentRoom();
        VoicechatConnection senderConn = api.getConnectionOf(senderUuid);
        if (senderConn != null && senderConn.getGroup() != null) {
            return senderConn.getGroup().getName();
        }
        return null;
    }

    public void unregisterListener() {
        PlayerAudioListener listener = audioListener;
        if (listener == null) return;
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api != null) api.unregisterAudioListener(listener);
        audioListener = null;
    }

    // ---- uplink ---------------------------------------------------------

    /**
     * Obtains VoicechatConnection for the player, marks them connected so SVC includes
     * them in proximity routing (required for Bedrock players without the mod),
     * then creates and registers an AudioSender.
     *
     * @return true if the sender was successfully registered, false if SVC's connection
     *         is not yet available (caller may retry after a short delay).
     */
    public boolean registerSender(UUID playerUuid) {
        if (audioSender != null) return true; // already registered — guard against double-call

        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api == null) {
            log().warn("registerSender skipped — SVC API not available, xuid={}", xuid);
            return false;
        }

        VoicechatConnection connection = api.getConnectionOf(playerUuid);
        if (connection == null) {
            log().warn("registerSender — no SVC VoicechatConnection yet for xuid={} uuid={} " +
                    "(SVC may not have processed the join yet; caller should retry)", xuid, playerUuid);
            return false;
        }

        // Create and register the AudioSender BEFORE calling setConnected(true).
        // Calling setConnected(true) first can make SVC treat the player as "mod-connected",
        // causing canSend() to return false and silently dropping all uplink audio.
        AudioSender sender = api.createAudioSender(connection);
        if (!api.registerAudioSender(sender)) {
            log().warn("registerSender — registerAudioSender() returned false for xuid={} uuid={}", xuid, playerUuid);
            return false;
        }

        if (!sender.canSend()) {
            // Player has the real SVC mod installed — they handle their own uplink.
            api.unregisterAudioSender(sender);
            log().info("Uplink rejected — xuid={} uuid={}: SVC mod installed, using mod audio path", xuid, playerUuid);
            send("{\"type\":\"uplink_rejected\",\"reason\":\"mod_installed\"}");
            return true; // intentional — no retry needed
        }

        // Mark connected only after confirming we are the audio sender, so SVC includes
        // this Bedrock player in proximity routing calculations.
        if (!connection.isInstalled()) {
            connection.setConnected(true);
            log().info("registerSender — setConnected(true) for Bedrock xuid={} uuid={}", xuid, playerUuid);
        }

        vcConnection = connection;
        audioSender  = sender;
        lastUplinkMs = System.currentTimeMillis();
        log().info("Audio sender registered — xuid={} uuid={}", xuid, playerUuid);
        return true;
    }

    public void unregisterSender() {
        AudioSender sender = audioSender;
        if (sender == null) return;
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api != null) api.unregisterAudioSender(sender);
        audioSender = null;

        VoicechatConnection connection = vcConnection;
        if (connection != null && !connection.isInstalled()) {
            connection.setConnected(false);
        }
        vcConnection = null;
    }

    /** Called from the WS server thread when an uplink frame arrives. */
    public void handleUplinkFrame(byte[] opus) {
        lastUplinkMs = System.currentTimeMillis();
        AudioSender sender = audioSender;
        if (sender != null) {
            sender.send(opus);
        } else {
            // Logged at most once per session to avoid flooding.
            if (uplinkWarnLogged.compareAndSet(false, true)) {
                log().warn("Uplink frame dropped — audioSender not ready for xuid={} (registerSender may have failed or is still pending)", xuid);
            }
        }
    }
    private final java.util.concurrent.atomic.AtomicBoolean uplinkWarnLogged = new java.util.concurrent.atomic.AtomicBoolean();

    /** Called by the idle-checker thread ~every 100 ms. Resets the sender if no frame arrived recently. */
    public void resetSenderIfIdle(long now, long thresholdMs) {
        AudioSender sender = audioSender;
        if (sender != null && now - lastUplinkMs > thresholdMs) {
            sender.reset();
        }
    }

    private static org.slf4j.Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }
}
