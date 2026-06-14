package com.sirilerklab.svcgeyser;

import com.sirilerklab.svcgeyser.command.SvcCommand;
import com.sirilerklab.svcgeyser.group.GroupManager;
import com.sirilerklab.svcgeyser.network.BridgeServer;
import com.sirilerklab.svcgeyser.player.XuidPlayerMap;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;
    private VoicechatServerApi voicechatServerApi;
    private XuidPlayerMap      xuidPlayerMap;
    private BridgeServer       bridgeServer;
    private GroupManager       groupManager;
    private String             jwtSecret;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        jwtSecret = getConfig().getString("jwt-secret", "change-me");
        int wsPort = getConfig().getInt("ws-port", 9000);

        xuidPlayerMap = new XuidPlayerMap();
        groupManager  = new GroupManager();
        bridgeServer  = new BridgeServer(wsPort);
        bridgeServer.start();

        getServer().getPluginManager().registerEvents(this, this);

        var svcCmd = getCommand("svc");
        if (svcCmd != null) {
            SvcCommand executor = new SvcCommand();
            svcCmd.setExecutor(executor);
            svcCmd.setTabCompleter(executor);
        }

        BukkitVoicechatService svc = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (svc != null) {
            svc.registerPlugin(new SvcGeyserVoicePlugin());
        } else {
            getSLF4JLogger().warn("Simple Voice Chat not found — voice features disabled");
        }
    }

    @Override
    public void onDisable() {
        if (bridgeServer != null) bridgeServer.closeAll();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        xuidPlayerMap.onJoin(uuid);
        String xuid = xuidPlayerMap.getXuid(uuid);
        if (xuid != null) {
            // Delay 20 ticks (1 s) so SVC has time to create its VoicechatConnection for
            // this player before we call getConnectionOf(). Bedrock players never send the
            // SVC UDP handshake, so without this wait the connection object is null.
            final String capturedXuid = xuid;
            getServer().getScheduler().runTaskLater(this,
                    () -> bridgeServer.notifyPlayerJoined(capturedXuid, uuid), 20L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String xuid = xuidPlayerMap.getXuid(uuid);
        if (xuid != null) bridgeServer.notifyPlayerQuit(xuid);
        xuidPlayerMap.onQuit(uuid);
    }

    void onSvcApiAcquired(VoicechatServerApi api) {
        this.voicechatServerApi = api;
        getSLF4JLogger().info("SVC API acquired — bridge ready");
    }

    public static Main getInstance()                    { return instance; }
    public VoicechatServerApi getVoicechatServerApi()   { return voicechatServerApi; }
    public XuidPlayerMap      getXuidPlayerMap()        { return xuidPlayerMap; }
    public BridgeServer       getBridgeServer()         { return bridgeServer; }
    public GroupManager       getGroupManager()         { return groupManager; }
    public String             getJwtSecret()            { return jwtSecret; }
}
