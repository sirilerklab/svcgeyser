package com.sirilerklab.svcgeyser;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

import java.util.UUID;

public class SvcGeyserVoicePlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "svcgeyser";
    }

    @Override
    public void initialize(VoicechatApi api) {}

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(CreateGroupEvent.class,            this::onGroupCreate);
        registration.registerEvent(JoinGroupEvent.class,              this::onGroupJoin);
        registration.registerEvent(LeaveGroupEvent.class,             this::onGroupLeave);
        registration.registerEvent(RemoveGroupEvent.class,            this::onGroupRemove);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        Main.getInstance().onSvcApiAcquired(event.getVoicechat());
    }

    private void onGroupCreate(CreateGroupEvent event) {
        Main.getInstance().getBridgeServer().scheduleBroadcastGroupUpdate();
    }

    private void onGroupJoin(JoinGroupEvent event) {
        var conn = event.getConnection();
        if (conn != null && conn.getPlayer() != null) {
            UUID uuid = conn.getPlayer().getUuid();
            String room = event.getGroup() != null ? event.getGroup().getName() : null;
            if (room != null) {
                Main.getInstance().getBridgeServer().notifyRoomChanged(uuid, room);
            }
        }
        Main.getInstance().getBridgeServer().scheduleBroadcastGroupUpdate();
    }

    private void onGroupLeave(LeaveGroupEvent event) {
        var conn = event.getConnection();
        if (conn != null && conn.getPlayer() != null) {
            UUID uuid = conn.getPlayer().getUuid();
            Main.getInstance().getBridgeServer().notifyRoomChanged(uuid, null);
        }
        Main.getInstance().getBridgeServer().scheduleBroadcastGroupUpdate();
    }

    private void onGroupRemove(RemoveGroupEvent event) {
        String name = event.getGroup().getName();
        Main.getInstance().getSLF4JLogger().info("Group removed: \"{}\"", name);
        Main.getInstance().getGroupManager().untrack(name);
        Main.getInstance().getBridgeServer().notifyGroupRemoved(name);
        Main.getInstance().getBridgeServer().scheduleBroadcastGroupUpdate();
    }
}
