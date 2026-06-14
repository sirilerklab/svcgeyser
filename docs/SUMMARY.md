# SVCGeyser — Project Summary

## What It Is

A Bedrock voice-chat bridge. Bedrock players (via GeyserMC/Floodgate) use a
companion Android app to join proximity voice chat alongside Java players using
the Simple Voice Chat (SVC) mod. Both sides hear each other — proximity, groups,
and whisper are all handled by SVC on the server.

---

## Repository Layout

```
svcgeyser/
├── plugin/          # Paper server plugin (Java 21, Gradle)
├── app/             # Android companion app (Kotlin, Gradle, Compose)
├── docs/DOCUMENT.md # Authoritative design + wire protocol spec
└── test/            # TypeScript WebSocket test harness (Bun)
```

Two independent modules — **no shared build system**. Build each separately.

---

## Current State — Phases 0–5 Complete

| Phase | Scope | Status |
|-------|-------|--------|
| 0 | Plugin scaffold, SVC API hook | ✅ Done |
| 1 | Floodgate XUID map, player join/quit events | ✅ Done |
| 2 | WebSocket bridge server (Java-WebSocket, shaded) | ✅ Done |
| 3 | Xbox/XSTS auth verification, session JWT | ✅ Done |
| 4 | SVC AudioSender (uplink) + AudioListener (downlink), group events | ✅ Done |
| 5 | Android app: OAuth login, WebSocket client, Opus audio engine, UI | ✅ Done |

---

## Plugin (`plugin/`)

### Build

```bash
cd plugin && ./gradlew build
# → plugin/build/libs/svcgeyser-*.jar
```

### Entry Points

| File | Role |
|------|------|
| `Main.java` | `JavaPlugin` — startup, Bukkit events, SVC service registration |
| `SvcGeyserVoicePlugin.java` | `VoicechatPlugin` — SVC event registration |
| `network/BridgeServer.java` | `WebSocketServer` — all app↔plugin signaling |
| `network/AppSession.java` | Per-connection state machine + audio sender/listener |
| `auth/XboxAuthVerifier.java` | Validates XSTS token, extracts XUID |
| `player/XuidPlayerMap.java` | Maps Floodgate XUID ↔ Java UUID for online players |
| `group/GroupManager.java` | Verifies group passwords (tracked + reflection); serialises group list to JSON |

### Config (`plugin/src/main/resources/config.yml`)

```yaml
ws-port: 9000
jwt-secret: change-me   # ← change before deploying
```

### Session State Machine

```
CONNECTED → (auth received) → AUTHED
                            ↓
                     player in game? → IN_GAME ←→ IN_ROOM
                            ↓ no
                     WAITING_FOR_PLAYER → IN_GAME (when player joins)
```

### Key Invariant — XUID is the join key

- App proves identity via Xbox XSTS token → plugin extracts XUID
- Floodgate exposes XUID of online Bedrock players
- Never trust gamertag strings

### Audio Flow

**Downlink (Java → Bedrock):**
SVC fires audio packets → `PlayerAudioListener` → serialize to binary frame
`[0x02][16B uuid][u8 flags][f32 spatial?][opus]` → send to app over WebSocket

**Uplink (Bedrock → Java):**
App sends `[0x01][u16 seq][opus]` → `BridgeServer.onMessage(ByteBuffer)` →
`AppSession.handleUplinkFrame()` → `AudioSender.send(opus)` → SVC routes to
nearby Java players via their SVC mod

### Known Timing Race — Uplink Sender Registration

SVC creates `VoicechatConnection` in its own `PlayerJoinEvent` handler.
If our handler runs first, `getConnectionOf()` returns null.

**Fix in place:**
1. `Main.onPlayerJoin` delays `notifyPlayerJoined` by **20 ticks (1 s)**
2. `enterInGame()` checks `registerSender()` return value; if false, **retries
   after another 20 ticks** on the Bukkit main thread
3. `setConnected(true)` is called **after** `canSend()` — calling it before
   can make SVC treat the player as "mod-connected", causing `canSend()` to
   return false and silently killing uplink

### Group Isolation (rooms)

A player in a voice group should hear **only** their group — never outside
proximity. Two mechanisms enforce this:

1. **Group type.** The app's "Create channel" UI (both the main screen and the
   bubble overlay) lets the user pick the SVC group type — **Normal / Open /
   Isolated**, defaulting to **Isolated**. The choice is sent as `groupType` and
   `handleJoinRoom` (`BridgeServer.java`) builds the group with it. Only
   `ISOLATED` fully blocks outside proximity audio; `NORMAL`/`OPEN` intentionally
   let members still hear nearby players (FIX.md "issue group 2"). Groups created
   by **Java players via the SVC mod UI** are client-side — the plugin can't force
   their type, so those users must manually pick "Isolated".
2. **Downlink filter for Bedrock listeners.** SVC's `PlayerAudioListener` fires
   before the client-side group filter, so `AppSession.groupAllows()` drops any
   packet whose sender's room differs from the listener's room (both read from
   volatile `currentRoom`, resolved live per packet). This covers what a Bedrock
   **app** user hears; ISOLATED groups cover what Java mod users hear.

### Group Passwords

SVC's API never exposes a group's password (`Group` has only `hasPassword()`) and
`setGroup(Group)` performs no password check, so **the bridge is the sole
enforcement point** for app users. `GroupManager.checkPassword` resolves the
expected password from one of two sources, then compares it to what the user typed:

1. **Bridge-created groups** → the password recorded in `track()` at create time.
2. **Externally-created groups** (made by a Java SVC-mod player) → SVC's own
   password, read **via reflection** (`reflectPassword`): unwrap the API `Group`'s
   internal `group` field, then its `password` field. (Technique credited in-code
   to TheodoreMeyer/SimpleVoice-Geyser.)

If neither route yields a password it **fails closed** (join denied), so an app
user can never join a protected group with a wrong/empty password. Field names are
SVC-version-specific (2.6.13); if a future build renames them, reflection fails
safely (deny + warning log) rather than allowing a bypass.

### Channel Creation Reliability

- **App side** (`BridgeClient.sendJoinRoom`) builds the `join_room` message with
  `JSONObject` rather than hand-rolled string concatenation — the old code dropped
  the closing quote on `groupType`, producing invalid JSON the plugin silently
  rejected, so **creating a channel never reached the server**.
- **Plugin side** (`handleJoinRoom`) runs all SVC group mutation (create / join /
  `setGroup` and the events it fires) on the **Bukkit main thread** inside a
  `try/catch`. SVC group creation off the main thread can throw, and an uncaught
  exception there is swallowed by the WebSocket library — leaving the app with no
  reply. The handler now always responds (`join_ok` or a `join_fail` reason,
  including `group_error` on unexpected failure), and logs the stack trace.

### Dependencies (all `compileOnly` — provided by server at runtime)

| Artifact | Version |
|----------|---------|
| `io.papermc.paper:paper-api` | `1.21.4-R0.1-SNAPSHOT` |
| `de.maxhenkel.voicechat:voicechat-api` | `2.6.13` |
| `org.geysermc.floodgate:api` | `core-repackage-2.2.5-SNAPSHOT` |
| `org.java-websocket:Java-WebSocket` | `1.5.7` (shaded, relocated) |
| `com.auth0:java-jwt` | `4.5.0` (shaded) |
| `com.google.code.gson:gson` | (shaded) |

---

## Android App (`app/`)

### Build

```bash
cd app && ./gradlew assembleDebug
# → app/app/build/outputs/apk/debug/app-debug.apk
```

### Entry Points

| File | Role |
|------|------|
| `MainActivity.kt` | Single-activity host; NavHost |
| `ui/viewmodel/AppViewModel.kt` | `AndroidViewModel` — all state + business logic |
| `network/BridgeClient.kt` | OkHttp WebSocket client |
| `audio/AudioEngine.kt` | AudioRecord → Opus encode → uplink; downlink → Opus decode → AudioTrack |
| `service/VoiceService.kt` | Foreground service (`foregroundServiceType=microphone`) |
| `auth/LiveOAuthHelper.kt` | Microsoft login.live.com PKCE OAuth via Custom Tabs |
| `auth/XboxAuthHelper.kt` | RPS → XSTS token exchange |
| `auth/OAuthRedirectActivity.kt` | Handles `svcgeyser://auth` redirect from browser |

### Screen Flow

```
LoginScreen → ServerConnectScreen → RoomListScreen
```

### Audio Format (must match SVC exactly)

- Codec: **Opus**
- Sample rate: **48 kHz mono**
- Frame size: **960 samples / 20 ms**
- PCM: 1920 bytes per frame (960 × 2 bytes, signed 16-bit)

### Concentus (Opus codec)

Pure-Java Opus implementation. JitPack can't build it (no root build file).

**Manual step required:** place `Concentus.jar` at `app/app/libs/Concentus.jar`
Download from: https://github.com/lostromb/concentus/releases/tag/v1.0-java

Already referenced in `build.gradle.kts`:
```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
```

### Auth Flow

1. `LoginScreen` → `LiveOAuthHelper.launchLogin()` → Custom Tabs (live.com)
2. Redirect to `svcgeyser://auth?code=…` → `OAuthRedirectActivity`
3. PKCE exchange → access token → Xbox RPS token → XSTS token
4. Auth header sent to plugin on WebSocket `onOpen`

### WebSocket Wire Protocol

**Signaling (JSON text frames)**

| Direction | Type | Key Fields |
|-----------|------|------------|
| App → Plugin | `auth` | `xstsHeader` |
| Plugin → App | `auth_ok` | `sessionToken`, `xuid` |
| Plugin → App | `player_joined_game` | `javaUuid` |
| Plugin → App | `status` | `inGame`, `javaUuid?`, `currentRoom?`, `groups[]` |
| Plugin → App | `group_update` | `groups[]` (each: `name`, `hasPassword`, `type`) |
| App → Plugin | `join_room` | `name`, `password?`, `groupType?` (`normal`/`open`/`isolated`) |
| App → Plugin | `leave_room` | — |
| Plugin → App | `join_ok` / `join_fail` | `reason` (`wrong_password`, `invalid_group_type`, `group_error`, …) |
| Plugin → App | `room_changed` | `room` (null = left) |
| Plugin → App | `uplink_rejected` | `reason` |

**Binary frames**

| Direction | Format |
|-----------|--------|
| App → Plugin (uplink) | `[0x01][u16 seq BE][opus bytes]` |
| Plugin → App (downlink) | `[0x02][16B uuid][u8 flags][f32 spatial if flags&0x04][opus bytes]` |

### Auto-Reconnect

Exponential backoff: 1 s → 2 s → 4 s … 30 s cap.
`connectionGeneration` counter prevents stale reconnect from firing after
explicit disconnect.

### Permissions Required

```
RECORD_AUDIO, INTERNET, WAKE_LOCK,
FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
SYSTEM_ALERT_WINDOW (bubble overlay — requested at runtime)
```

---

## Deploying to a Server

1. Install on Paper 1.21.4: **GeyserMC**, **Floodgate**, **Simple Voice Chat**
2. Drop `plugin/build/libs/svcgeyser-*.jar` into `plugins/`
3. Set `jwt-secret` in `plugins/SVCGeyser/config.yml`
4. Port `9000` (or configured `ws-port`) must be reachable from players' devices
5. Install APK on Android device; log in with Microsoft account

---

## Pending / Known Issues

| Issue | Notes |
|-------|-------|
| `Concentus.jar` must be placed manually | See path above |
| `uplink_rejected` not handled in app | App keeps sending frames; plugin has already unregistered sender |
| Uplink fix is still speculative | If uplink still fails after deploy, check Minecraft console for "Audio sender registered" or "Uplink frame dropped" log lines |
| Partial Phase 6 | Group create/join with selectable type + password verification done; whisper and other Phase 6 items not started |

---

## Useful Log Lines to Watch (Minecraft console)

```
[SVCGeyser] SVC API acquired — bridge ready        ← SVC hooked OK
[SVCGeyser] App connected from <ip>                ← WebSocket open
[SVCGeyser] Auth OK — xuid=... already in-game    ← auth path
[SVCGeyser] Audio listener registered — xuid=...  ← downlink ready
[SVCGeyser] Audio sender registered — xuid=...    ← uplink ready ✅
[SVCGeyser] xuid=... join_room name="..." hasPassword=... groupType=...  ← create/join received
[SVCGeyser] xuid=... created room "..." type=... (password=...)          ← group created ✅
[SVCGeyser] registerSender — no SVC VoicechatConnection yet  ← timing race (will retry)
[SVCGeyser] Uplink frame dropped — audioSender not ready     ← sender never registered ❌
[SVCGeyser] Uplink rejected — ... mod installed             ← Bedrock player has SVC mod
```
