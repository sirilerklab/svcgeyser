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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    /** 36 random bytes encode to exactly 48 base64 characters (4 chars per 3 bytes). */
    private static final int SECRET_BYTES = 36;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
        // On first run config.yml has no secret, so generate a cryptographically-random
        // 48-character base64 secret and persist it. On later runs the saved secret is reused.
        jwtSecret = loadOrCreateSessionSecret();

        int wsPort = getConfig().getInt("ws-port", 9000);

        xuidPlayerMap = new XuidPlayerMap();
        groupManager  = new GroupManager();
        bridgeServer  = new BridgeServer(wsPort);
        enableBridgeTls();
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

    /**
     * Returns the JWT secret from config.yml, generating and persisting a new one on first run
     * (when the key is missing or blank). Subsequent runs reuse the saved secret.
     */
    private String loadOrCreateSessionSecret() {
        String secret = getConfig().getString("jwt-secret", "");
        if (secret == null || secret.isBlank()) {
            secret = generateSessionSecret();
            getConfig().set("jwt-secret", secret);
            saveConfig();
            getSLF4JLogger().info("No JWT secret found — generated a 48-character base64 secret and saved it to config.yml");
        } else {
            getSLF4JLogger().info("Loaded existing JWT secret from config.yml");
        }
        return secret;
    }

    /** Loads/creates the PKCS12 keystore and enables TLS (WSS) on the bridge server. */
    private void enableBridgeTls() {
        try {
            String keystorePassword = loadOrCreateKeystorePassword();
            javax.net.ssl.SSLContext ssl =
                    com.sirilerklab.svcgeyser.network.CertManager.loadOrCreate(getDataFolder(), keystorePassword);
            bridgeServer.enableSsl(ssl);
            getSLF4JLogger().info("WebSocket TLS (WSS) enabled — PKCS12 keystore in {} (cert.pem / cert.key exported)",
                    getDataFolder().getName());
        } catch (Exception e) {
            getSLF4JLogger().error("Failed to enable WebSocket TLS — the bridge will not start securely", e);
        }
    }

    /** Returns the PKCS12 keystore password from config.yml, generating one on first run. */
    private String loadOrCreateKeystorePassword() {
        String pw = getConfig().getString("keystore-password", "");
        if (pw == null || pw.isBlank()) {
            pw = generateSessionSecret();
            getConfig().set("keystore-password", pw);
            saveConfig();
            getSLF4JLogger().info("Generated a new keystore password and saved it to config.yml");
        }
        return pw;
    }

    /** Generates a cryptographically-random 48-character base64 secret (36 random bytes). */
    private static String generateSessionSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    void onSvcApiAcquired(VoicechatServerApi api) {
        this.voicechatServerApi = api;
        getSLF4JLogger().info("SVC API acquired — bridge ready");
    }

    /**
     * Re-reads config.yml from disk and refreshes the cached config values that can be
     * applied at runtime. The WebSocket port (ws-port) is bound at startup, so a change to
     * it only takes effect after a full server/plugin restart.
     */
    public void reloadPluginConfig() {
        reloadConfig();
        jwtSecret = getConfig().getString("jwt-secret", "change-me");
        getSLF4JLogger().info("Configuration reloaded from config.yml");
    }

    public static Main getInstance()                    { return instance; }
    public VoicechatServerApi getVoicechatServerApi()   { return voicechatServerApi; }
    public XuidPlayerMap      getXuidPlayerMap()        { return xuidPlayerMap; }
    public BridgeServer       getBridgeServer()         { return bridgeServer; }
    public GroupManager       getGroupManager()         { return groupManager; }
    public String             getJwtSecret()            { return jwtSecret; }
}
