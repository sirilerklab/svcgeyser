package com.sirilerklab.svcgeyser.group;

import com.sirilerklab.svcgeyser.Main;
import com.sirilerklab.svcgeyser.network.AppSession;
import com.sirilerklab.svcgeyser.network.BridgeServer;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-channel member rosters and who is currently speaking.
 * Roster building uses Bukkit/SVC APIs and must run on the main thread.
 */
public class RoomRosterTracker {

    public static final long SPEAKING_TIMEOUT_MS = 300;

    public record Member(UUID uuid, String name) {}

    private final ConcurrentHashMap<UUID, Long> lastSpokeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Member>> cachedRosters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> lastSpeakingByRoom = new ConcurrentHashMap<>();

    public void markSpeaking(UUID playerUuid) {
        if (playerUuid != null) {
            lastSpokeMs.put(playerUuid, System.currentTimeMillis());
        }
    }

    public void cacheRoster(String roomName, List<Member> members) {
        if (roomName != null) {
            cachedRosters.put(roomName, List.copyOf(members));
        }
    }

    public void clearRoom(String roomName) {
        if (roomName == null) return;
        cachedRosters.remove(roomName);
        lastSpeakingByRoom.remove(roomName);
    }

    /** Builds the member list for a voice channel. Must run on the Bukkit main thread. */
    public List<Member> buildRoster(String roomName, BridgeServer bridge) {
        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api == null || roomName == null) return List.of();

        LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String room = resolveRoom(api, bridge, uuid);
            if (roomName.equals(room)) {
                members.put(uuid, player.getName());
            }
        }
        List<Member> result = new ArrayList<>(members.size());
        for (var entry : members.entrySet()) {
            result.add(new Member(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static String resolveRoom(VoicechatServerApi api, BridgeServer bridge, UUID javaUuid) {
        AppSession session = bridge.sessionForPlayer(javaUuid);
        if (session != null) {
            String room = session.getCurrentRoom();
            if (room != null) return room;
        }
        VoicechatConnection conn = api.getConnectionOf(javaUuid);
        if (conn == null) return null;
        Group group = conn.getGroup();
        return group != null ? group.getName() : null;
    }

    public Set<String> computeSpeaking(List<Member> members, long now) {
        Set<String> speaking = new LinkedHashSet<>();
        for (Member m : members) {
            Long last = lastSpokeMs.get(m.uuid());
            if (last != null && now - last < SPEAKING_TIMEOUT_MS) {
                speaking.add(m.uuid().toString());
            }
        }
        return speaking;
    }

    /**
     * Diffs speaking state for a room and returns JSON to broadcast, or null if unchanged.
     * Safe to call from the idle-checker thread (uses cached roster only).
     */
    public String tickSpeaking(String roomName, long now) {
        List<Member> members = cachedRosters.get(roomName);
        if (members == null) return null;

        Set<String> current = computeSpeaking(members, now);
        Set<String> previous = lastSpeakingByRoom.getOrDefault(roomName, Set.of());
        if (current.equals(previous)) return null;

        lastSpeakingByRoom.put(roomName, current);
        return toSpeakingJson(roomName, current);
    }

    public void seedSpeaking(String roomName, List<Member> members, long now) {
        lastSpeakingByRoom.put(roomName, computeSpeaking(members, now));
    }

    public static String toRosterJson(String roomName, List<Member> members) {
        StringBuilder sb = new StringBuilder("{\"type\":\"room_roster\",\"room\":");
        appendRoomField(sb, roomName);
        sb.append(",\"members\":[");
        boolean first = true;
        for (Member m : members) {
            if (!first) sb.append(",");
            sb.append("{\"uuid\":\"").append(m.uuid()).append("\",\"name\":\"")
              .append(GroupManager.escapeJson(m.name())).append("\"}");
            first = false;
        }
        return sb.append("]}").toString();
    }

    public static String toSpeakingJson(String roomName, Set<String> speakingUuids) {
        StringBuilder sb = new StringBuilder("{\"type\":\"speaking_update\",\"room\":");
        appendRoomField(sb, roomName);
        sb.append(",\"speaking\":[");
        boolean first = true;
        for (String uuid : speakingUuids) {
            if (!first) sb.append(",");
            sb.append("\"").append(uuid).append("\"");
            first = false;
        }
        return sb.append("]}").toString();
    }

    public static String emptyRosterJson() {
        return "{\"type\":\"room_roster\",\"room\":null,\"members\":[]}";
    }

    public static String emptySpeakingJson() {
        return "{\"type\":\"speaking_update\",\"room\":null,\"speaking\":[]}";
    }

    private static void appendRoomField(StringBuilder sb, String roomName) {
        if (roomName == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(GroupManager.escapeJson(roomName)).append("\"");
        }
    }

    /** Returns all room names that currently have a cached roster. */
    public Set<String> cachedRooms() {
        return Set.copyOf(cachedRosters.keySet());
    }
}
