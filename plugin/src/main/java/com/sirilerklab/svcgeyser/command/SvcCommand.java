package com.sirilerklab.svcgeyser.command;

import com.sirilerklab.svcgeyser.Main;
import com.sirilerklab.svcgeyser.network.AppSession;
import com.sirilerklab.svcgeyser.network.BridgeServer;
import com.sirilerklab.svcgeyser.player.XuidPlayerMap;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SvcCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("svcgeyser.reload")) {
                sender.sendMessage("§cYou don't have permission to reload SVCGeyser.");
                return true;
            }
            Main.getInstance().reloadPluginConfig();
            sender.sendMessage("§aSVCGeyser configuration reloaded. §7(ws-port changes require a restart.)");
            return true;
        }

        if (!sender.hasPermission("svcgeyser.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (!sub.equals("status")) {
            sender.sendMessage("§eUsage: /svc <status [player]|reload>");
            return true;
        }

        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[1]);
                return true;
            }
            sendPlayerStatus(sender, target);
            return true;
        }

        sendAllStatus(sender);
        return true;
    }

    private void sendAllStatus(CommandSender sender) {
        BridgeServer bridge = Main.getInstance().getBridgeServer();
        XuidPlayerMap map = Main.getInstance().getXuidPlayerMap();
        Set<String> shown = new HashSet<>();

        sender.sendMessage("§6--- SVCGeyser bridge status ---");

        for (AppSession session : bridge.getAllSessions()) {
            String xuid = session.getXuid();
            if (xuid == null) continue;
            shown.add(xuid);
            UUID javaUuid = map.getJavaUuid(xuid);
            String name = javaUuid != null ? playerName(javaUuid) : "(not in server)";
            sender.sendMessage(formatLine(name, xuid, javaUuid != null, session));
        }

        for (var entry : map.snapshot().entrySet()) {
            if (shown.contains(entry.getKey())) continue;
            String name = playerName(entry.getValue());
            sender.sendMessage(formatLine(name, entry.getKey(), true, null));
        }

        if (shown.isEmpty() && map.snapshot().isEmpty()) {
            sender.sendMessage("§7No Bedrock players or app sessions.");
        }
    }

    private void sendPlayerStatus(CommandSender sender, Player target) {
        FloodgateApi floodgate = FloodgateApi.getInstance();
        if (floodgate == null || !floodgate.isFloodgatePlayer(target.getUniqueId())) {
            sender.sendMessage("§c" + target.getName() + " is not a Bedrock (Floodgate) player.");
            return;
        }

        String xuid = floodgate.getPlayer(target.getUniqueId()).getXuid();
        AppSession session = Main.getInstance().getBridgeServer().findSessionByXuid(xuid);
        sender.sendMessage("§6--- " + target.getName() + " ---");
        sender.sendMessage(formatLine(target.getName(), xuid, true, session));
    }

    private static String formatLine(String name, String xuid, boolean inServer, AppSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("§f").append(name).append(" §7(xuid=").append(xuid).append(")");
        sb.append(" §8| §7In server: ").append(inServer ? "§ayes" : "§cno");

        if (session == null) {
            sb.append(" §8| §7App: §cnot connected");
            return sb.toString();
        }

        sb.append(" §8| §7App: §aconnected");
        sb.append(" §8| §7State: §f").append(session.getState().name());

        String group = resolveGroupName(session);
        sb.append(" §8| §7Group: §f").append(group != null ? group : "none");
        sb.append(" §8| §7Muted: ").append(session.isMuted() ? "§cyes" : "§7no");
        sb.append(" §8| §7Deafened: ").append(session.isDeafened() ? "§cyes" : "§7no");
        sb.append(" §8| §7Sender: ").append(session.hasAudioSender() ? "§ayes" : "§cno");
        sb.append(" §8| §7Listener: ").append(session.hasAudioListener() ? "§ayes" : "§cno");
        return sb.toString();
    }

    private static String resolveGroupName(AppSession session) {
        String room = session.getCurrentRoom();
        if (room != null) return room;

        String xuid = session.getXuid();
        if (xuid == null) return null;
        UUID javaUuid = Main.getInstance().getXuidPlayerMap().getJavaUuid(xuid);
        if (javaUuid == null) return null;

        VoicechatServerApi api = Main.getInstance().getVoicechatServerApi();
        if (api == null) return null;
        VoicechatConnection conn = api.getConnectionOf(javaUuid);
        if (conn == null) return null;
        Group group = conn.getGroup();
        return group != null ? group.getName() : null;
    }

    private static String playerName(UUID javaUuid) {
        Player p = Bukkit.getPlayer(javaUuid);
        return p != null ? p.getName() : javaUuid.toString().substring(0, 8) + "…";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("svcgeyser.status")) subs.add("status");
            if (sender.hasPermission("svcgeyser.reload")) subs.add("reload");
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status")
                && sender.hasPermission("svcgeyser.status")) {
            return filter(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                    args[1]
            );
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(lower)) out.add(o);
        }
        return out;
    }
}
