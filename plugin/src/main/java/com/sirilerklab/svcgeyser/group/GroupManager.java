package com.sirilerklab.svcgeyser.group;

import com.sirilerklab.svcgeyser.Main;
import de.maxhenkel.voicechat.api.Group;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies group passwords for app users. SVC's API exposes only {@link Group#hasPassword()}
 * (never the password) and {@code VoicechatConnection.setGroup(Group)} performs no password
 * check, so the bridge is the sole enforcement point for app users.
 * For bridge-created groups the password is taken from {@link #passwords}; for groups created
 * elsewhere (e.g. by a Java SVC-mod player) it is read reflectively from the SVC group impl.
 * If the password cannot be determined by either route, the join is denied (fail closed).
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
     * The expected password is the bridge-tracked one, or — for groups the bridge did not
     * create — the SVC group's own password read via reflection. Fails closed: if neither
     * route yields a password the join is denied, so app users can never join a protected
     * group with an arbitrary/incorrect password.
     */
    public boolean checkPassword(Group group, String provided) {
        if (!group.hasPassword()) return true;
        String stored = passwords.get(group.getName());
        if (stored == null) stored = reflectPassword(group); // not bridge-created — read SVC's own
        if (stored == null) return false;                    // couldn't determine — deny
        return stored.equals(provided != null ? provided : "");
    }

    /**
     * Reads the plaintext password from SVC's internal group object via reflection. The API
     * {@link Group} is a thin wrapper holding the real server group in a {@code group} field,
     * which in turn has a {@code password} field. SVC exposes no API for this. Returns null if
     * the password cannot be read (unexpected impl/obfuscation) — callers then fail closed.
     *
     * <p>Reflection technique adapted from SimpleVoice-Geyser by Theodore Meyer:
     * <a href="https://github.com/TheodoreMeyer/SimpleVoice-Geyser/blob/master/core/src/main/java/io/github/theodoremeyer/simplevoicegeyser/core/managers/GroupManager.java#L155-L171">GroupManager.java (L155-171)</a>.
     */
    private static String reflectPassword(Group group) {
        try {
            Field groupField = group.getClass().getDeclaredField("group");
            groupField.setAccessible(true);
            Object groupObject = groupField.get(group);

            Field passwordField = groupObject.getClass().getDeclaredField("password");
            passwordField.setAccessible(true);
            return (String) passwordField.get(groupObject);
        } catch (Throwable e) {
            Main.getInstance().getSLF4JLogger().warn(
                    "Failed to reflect password of group \"{}\" ({}): {}",
                    group.getName(), group.getId(), e.getMessage());
            return null;
        }
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
