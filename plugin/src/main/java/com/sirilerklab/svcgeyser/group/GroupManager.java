package com.sirilerklab.svcgeyser.group;

import de.maxhenkel.voicechat.api.Group;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks passwords for groups created via the bridge so that subsequent joiners
 * can be verified. Groups created by Java SVC-mod players are not in this map;
 * those are trusted as-is (we can't retrieve their password from the API).
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
     * For groups not managed by the bridge the check is skipped (return true) since
     * we have no stored password to verify against.
     */
    public boolean checkPassword(Group group, String provided) {
        if (!group.hasPassword()) return true;
        String stored = passwords.get(group.getName());
        if (stored == null) return true; // externally-created group — can't verify, allow
        return stored.equals(provided != null ? provided : "");
    }

    /** Serializes the group collection to a JSON array: [{name, hasPassword}, ...] */
    public static String toJson(Collection<Group> groups) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Group g : groups) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(escape(g.getName()))
              .append("\",\"hasPassword\":").append(g.hasPassword()).append("}");
            first = false;
        }
        return sb.append("]").toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Escapes a string for use inside a JSON value. */
    public static String escapeJson(String s) {
        return escape(s);
    }
}
