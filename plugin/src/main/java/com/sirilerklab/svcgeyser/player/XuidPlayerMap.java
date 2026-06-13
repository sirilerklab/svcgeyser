package com.sirilerklab.svcgeyser.player;

import com.sirilerklab.svcgeyser.Main;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class XuidPlayerMap {
    private final ConcurrentHashMap<String, UUID> xuidToUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> uuidToXuid = new ConcurrentHashMap<>();

    public void onJoin(UUID javaUuid) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (api == null || !api.isFloodgatePlayer(javaUuid)) return;
        String xuid = api.getPlayer(javaUuid).getXuid();
        xuidToUuid.put(xuid, javaUuid);
        uuidToXuid.put(javaUuid, xuid);
        log().info("Bedrock player joined — xuid={} uuid={}", xuid, javaUuid);
    }

    public void onQuit(UUID javaUuid) {
        String xuid = uuidToXuid.remove(javaUuid);
        if (xuid != null) {
            xuidToUuid.remove(xuid);
            log().info("Bedrock player quit — xuid={} uuid={}", xuid, javaUuid);
        }
    }

    public UUID getJavaUuid(String xuid) {
        return xuidToUuid.get(xuid);
    }

    public String getXuid(UUID javaUuid) {
        return uuidToXuid.get(javaUuid);
    }

    private static org.slf4j.Logger log() {
        return Main.getInstance().getSLF4JLogger();
    }
}
