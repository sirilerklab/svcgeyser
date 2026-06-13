# SVCGeyser

Bedrock voice-chat bridge for **PaperMC** servers running **GeyserMC**, **Floodgate**, and **Simple Voice Chat (SVC)**.

Bedrock players connect through a companion **Android app**. Their microphone audio is injected into SVC on the server, and they hear everything SVC would normally send to a Java mod client — proximity chat, voice groups, and whisper. Java players with the SVC mod and Bedrock players using the app can talk to each other in the same world.

**XUID is the join key.** The app proves identity with a Microsoft / Xbox token. Floodgate maps that XUID to the in-game Bedrock player. Gamertag strings are never trusted.

---

## Supported platform

| Component | Version |
|-----------|---------|
| Server | **Paper** 1.21.4 (Java 21) |
| [GeyserMC](https://geysermc.org/) | Bedrock proxy (required) |
| [Floodgate](https://wiki.geysermc.org/floodgate/) | XUID linkage (soft dependency) |
| [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) | ≥ 2.6.x Bukkit/Paper plugin (soft dependency) |
| Companion app | Android 7.0+ (API 24), target SDK 36 |

Spigot or other forks may work if SVC and Floodgate load correctly, but the plugin is built and tested against **Paper 1.21.4**.

---

## How it works

```
Android app  ── WebSocket (JSON + Opus) ──  SVCGeyser plugin  ── SVC API ──  Java SVC mod clients
 (Kotlin)                                      (Paper)              (UDP)
```

1. The app signs in with a Microsoft account and obtains an Xbox XSTS token.
2. The app opens a WebSocket to the plugin (`ws://<server-ip>:9000` by default).
3. The plugin verifies the token, issues a session JWT, and waits for the matching Bedrock player to join (via Floodgate XUID).
4. When the player is in-game, the plugin registers an SVC **AudioSender** (mic → game) and **PlayerAudioListener** (game → app).
5. Voice groups in the app map 1:1 to SVC groups on the server.

Audio format (fixed by SVC): **Opus, 48 kHz mono, 20 ms frames** (960 samples per packet).

Full wire protocol: [`docs/DOCUMENT.md`](docs/DOCUMENT.md).

---

## Server installation

### 1. Prerequisites

Install on your Paper server (in `plugins/`):

1. **GeyserMC** — Bedrock players can join the Java server.
2. **Floodgate** — exposes Bedrock XUIDs to plugins.
3. **Simple Voice Chat** — Bukkit/Paper jar (not the Fabric/Forge mod alone).

Start the server once so all three plugins create their configs.

### 2. Install SVCGeyser

**Option A — download a release jar** (if published), or **Option B — build from source:**

```bash
cd plugin
./gradlew shadowJar
```

Copy the fat jar from `plugin/build/libs/svcgeyser-*.jar` into your server `plugins/` folder.

### 3. Configure

After first start, edit `plugins/SVCGeyser/config.yml`:

```yaml
ws-port: 9000
jwt-secret: "change-me-to-a-long-random-secret"
```

| Setting | Description |
|---------|-------------|
| `ws-port` | WebSocket port the Android app connects to (default `9000`). |
| `jwt-secret` | **Change before production.** Used to sign app session tokens. |

### 4. Open the WebSocket port

- The app connects to `ws://<your-server-ip>:<ws-port>`.
- Ensure the port is reachable from players’ phones (firewall / router port-forward if self-hosted).
- For public internet deployment over TLS, put a reverse proxy in front and use `wss://` (not built into the app yet — plain `ws://` for local/LAN use).

### 5. Verify plugin load

Check the server console for:

```
[SVCGeyser] SVC API acquired — bridge ready
[SVCGeyser] Bridge WS server listening on port 9000
```

If SVC or Floodgate is missing, the bridge will not function correctly.

---

## Android app installation

### Build from source

**1. Opus library (required once):**

Download [Concentus v1.0-java](https://github.com/lostromb/concentus/releases/tag/v1.0-java) and place the JAR at:

```
app/app/libs/Concentus.jar
```

**2. Build debug APK:**

```bash
cd app
./gradlew assembleDebug
```

Output: `app/app/build/outputs/apk/debug/app-debug.apk`

Install the APK on the player’s Android device (sideload or adb).

### App permissions

The app will request:

- **Microphone** — voice capture
- **Display over other apps** — optional floating bubble overlay while in Minecraft
- **Internet** — WebSocket to the server

---

## Usage (players)

### First-time setup

1. **Sign in** with your Microsoft / Xbox account (one-time; session is saved until the refresh token expires).
2. **Add a server** on the connect screen (label, IP/hostname, port) or use **Quick connect**.
3. Tap a saved server or **Connect** to join the bridge.

### In-game flow

1. Join the Minecraft server on Bedrock (via Geyser) with the **same Microsoft account** you used in the app.
2. Wait until the app shows **In game** (it detects your Bedrock player via XUID).
3. Grant **microphone** permission when prompted — voice starts automatically.
4. **Join a voice channel** from the room list, or **Create channel** to start a new SVC group.
5. Use the control bar to **mute**, **deafen**, or switch **speaker / earpiece**.
6. Optional: enable the **bubble overlay** to control voice while Minecraft is in the foreground.

### Voice channels

- Channels listed in the app are live SVC groups on the server.
- Creating a channel from the app creates the group on the server (create-if-missing on `join_room`).
- Password-protected groups are supported when joining from the app.
- If a Java player creates, joins, leaves, or removes a group in-game, the app list updates automatically.

### Disconnect / sign out

- **Power icon** on the room screen disconnects from the bridge (returns to server list).
- **Sign out** on the server connect screen clears the saved Microsoft login.

---

## Repository layout

```
SVCGeyser/
├── plugin/          # Paper plugin (Java 21, Gradle)
├── app/             # Android companion (Kotlin, Jetpack Compose)
├── docs/DOCUMENT.md # Design doc + wire protocol spec
└── test/            # TypeScript WebSocket test harness (Bun)
```

The two modules build independently — there is no root Gradle project.

### Development commands

**Plugin:**

```bash
cd plugin && ./gradlew shadowJar
```

**App:**

```bash
cd app && ./gradlew assembleDebug   # APK
cd app && ./gradlew test            # unit tests
cd app && ./gradlew lint            # lint
```

**Test harness** (signaling only, no audio):

```bash
cd test && bun run index.ts
```

---

## Troubleshooting

### App stuck on “Waiting for player to join server”

- Bedrock player must be **online** on the same server.
- Microsoft account in the app must match the account linked to the Bedrock profile Floodgate sees.
- Geyser + Floodgate must be running; check Floodgate links the correct XUID.

### No audio from mic (Java players can’t hear Bedrock)

Watch the server console:

| Log line | Meaning |
|----------|---------|
| `Audio sender registered` | Uplink OK |
| `registerSender — no SVC VoicechatConnection yet` | Timing race; plugin retries automatically |
| `Uplink frame dropped — audioSender not ready` | Sender never registered — check SVC is loaded |
| `Uplink rejected — mod installed` | Player has SVC mod on client; bridge uplink is disabled |

### No audio from server (Bedrock can’t hear Java)

- Confirm `Audio listener registered` in console.
- Check app microphone permission and that mute/deafen are off.
- Ensure the Bedrock character is within SVC proximity range of speakers.

### WebSocket connection fails

- Verify `ws-port` is open and matches the port in the app.
- Use the server’s **public IP** or hostname reachable from the phone (not `localhost` unless testing on the same machine via adb reverse).
- Check `jwt-secret` is set; auth fails if the plugin cannot issue tokens.

### Auth / login issues

- First login opens Microsoft login in the browser (Chrome Custom Tabs).
- If silent restore fails, use **Sign in with Microsoft** again.
- Redirect URI is `svcgeyser://auth` — do not block custom-tab redirects.

---

## Implementation status

| Phase | Scope | Status |
|-------|-------|--------|
| 0 | Plugin scaffold, Gradle, `plugin.yml` | Done |
| 1 | Floodgate XUID map, Xbox auth, JWT sessions | Done |
| 2 | WebSocket `BridgeServer`, `AppSession` state machine | Done |
| 3 | SVC `AudioSender` / `PlayerAudioListener`, uplink + downlink | Done |
| 4 | Voice groups, `group_update`, join/leave rooms | Done |
| 5 | Android app: OAuth, audio engine, UI, bubble overlay | Done |
| 6 | Rate limiting, metrics, `wss://` for public deploy | Planned |

---

## License & credits

- **Simple Voice Chat** — [maxhenkel](https://github.com/MaxHenkel) / [modrepo.de API](https://voicechat.modrepo.de)
- **GeyserMC / Floodgate** — [GeyserMC](https://github.com/GeyserMC)
- **Concentus** — pure-Java Opus ([lostromb/concentus](https://github.com/lostromb/concentus))

Design and protocol details: [`docs/DOCUMENT.md`](docs/DOCUMENT.md).
