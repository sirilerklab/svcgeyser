package com.sirilerklab.svcgeyser.group;

import de.maxhenkel.voicechat.api.Group;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks passwords for groups created via the bridge so that subsequent joiners
 * can be verified. SVC's API exposes only {@link Group#hasPassword()} (never the
 * password itself) and {@code VoicechatConnection.setGroup(Group)} performs no
 * password check, so the bridge is the sole enforcement point for app users.
 * Password-protected groups the bridge did not create (e.g. made by a Java SVC-mod
 * player) cannot be verified, so joining them is denied (fail closed).
 */
public class GroupManager {

    private final ConcurrentHashMap<String, String> passwords = new ConcurrentHashMap<>();

    /** Record a bridge-created group's password (empty string = no password). */
    public void track(String groupName, String password) {
        passwords.put(groupName, password != null ? password : "");
    }

    /** Forget a group (e.g. after it is removed). */
    public void untrack(String groupName) {
        passwords.remove(groupName);
    }

    /**
     * Returns true if the provided password satisfies the group's password requirement.
     * Fails closed: a password-protected group with no stored password (one the bridge
     * did not create, so we cannot verify against it) is rejected rather than allowed,
     * preventing app users from joining with an arbitrary/incorrect password.
     */
    public boolean checkPassword(Group group, String provided) {
        if (!group.hasPassword()) return true;
        String stored = passwords.get(group.getName());
        if (stored == null) return false; // password-protected but unverifiable — deny
        return stored.equals(provided != null ? provided : "");
    }

    /** Serializes the group collection to a JSON array: [{name, hasPassword}, ...] */
    public static String toJson(Collection<Group> groups) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Group g : groups) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(escape(g.getName()))
              .append("\",\"hasPassword\":").append(g.hasPassword())
              .append(",\"type\":\"").append(typeWire(g.getType())).append("\"}");
            first = false;
        }
        return sb.append("]").toString();
    }

    private static String typeWire(Group.Type type) {
        if (type == Group.Type.OPEN) return "open";
        if (type == Group.Type.ISOLATED) return "isolated";
        return "normal";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Escapes a string for use inside a JSON value. */
    public static String escapeJson(String s) {
        return escape(s);
    }
}
