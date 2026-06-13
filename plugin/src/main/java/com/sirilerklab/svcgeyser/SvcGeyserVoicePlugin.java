package com.sirilerklab.svcgeyser;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

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

    // Push a group_update broadcast on every group lifecycle change.
    private void onGroupCreate(CreateGroupEvent event) {
        Main.getInstance().getBridgeServer().broadcastGroupUpdate();
    }

    private void onGroupJoin(JoinGroupEvent event) {
        Main.getInstance().getBridgeServer().broadcastGroupUpdate();
    }

    private void onGroupLeave(LeaveGroupEvent event) {
        Main.getInstance().getBridgeServer().broadcastGroupUpdate();
    }

    // Fired when SVC destroys an empty group — this is the event that was missing,
    // causing the mobile to never see group deletions.
    private void onGroupRemove(RemoveGroupEvent event) {
        Main.getInstance().getSLF4JLogger().info("Group removed: \"{}\"", event.getGroup().getName());
        Main.getInstance().getBridgeServer().broadcastGroupUpdate();
    }
}
