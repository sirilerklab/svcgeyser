# Bedrock Voice Chat Bridge — Agent Context & Implementation Plan

> Context document for Claude CLI. Goal: let Bedrock players (via GeyserMC) use voice chat
> through a companion mobile app, bridged into **Simple Voice Chat (SVC)** on the Java side.
> Result: Bedrock (app) players and Java (SVC mod) players hear each other, with proximity,
> groups, and whisper handled entirely by SVC.

---

## 1. System Overview

```
┌─────────────┐  WebSocket (signaling)   ┌──────────────────────────┐
│ Companion    │  + UDP/WS (Opus frames)  │ Paper server              │
│ App (Android)│◄────────────────────────►│  ├─ GeyserMC + Floodgate  │
│  - Xbox login│                          │  ├─ Simple Voice Chat     │
│  - Mic/Spkr  │                          │  └─ Bridge Plugin (ours)  │
└─────────────┘                          └──────────────────────────┘
        │  MSAL OAuth → XSTS token
        ▼
   Microsoft / Xbox Live (XUID identity)
```

Components to build:
1. **Bridge Plugin** (Java, Paper plugin) — registers as an SVC `VoicechatPlugin`, exposes a
   WebSocket/UDP endpoint for apps, maps app sessions ↔ in-game players via XUID.
2. **Companion App** (Android, Kotlin) — Xbox login, server connect (IP:port), room/group join,
   mic capture → Opus encode → send; receive → Opus decode → playback.
3. **Auth verifier** (inside plugin or small backend) — validates Xbox XSTS tokens and extracts XUID.

Key invariant: **XUID is the join key.** App proves XUID via Xbox token; Floodgate exposes the
XUID of online Bedrock players. Never trust gamertag strings.

---

## 2. Simple Voice Chat API Reference (v2.6.x)

Javadoc root: `https://voicechat.modrepo.de` • Maven artifact: `de.maxhenkel.voicechat:voicechat-api`

### 2.1 Plugin lifecycle (Bukkit/Paper)
- Implement `de.maxhenkel.voicechat.api.VoicechatPlugin`:
  - `getPluginId()` → unique string id.
  - `initialize(VoicechatApi api)` → called once.
  - `registerEvents(EventRegistration registration)` → register event listeners.
- On Bukkit, register via `BukkitVoicechatService` (obtained from Bukkit `ServicesManager`)
  in `onEnable()`: `getServer().getServicesManager().load(BukkitVoicechatService.class).registerPlugin(myPlugin)`.
- Get `VoicechatServerApi` from the `VoicechatServerStartedEvent` (package `api.events`) —
  cache it; it is the entry point for everything below.

### 2.2 Audio format (critical)
- SVC audio is **Opus**, 48,000 Hz, mono, 16-bit PCM source, **20 ms frames = 960 samples
  (1920 bytes PCM) per packet**.
- The app must encode mic audio to Opus 48k mono, 20 ms frames, and decode the same on receive.
- Server-side Opus helpers exist in package `api.opus` (`OpusEncoder`, `OpusDecoder` created
  via `VoicechatApi.createEncoder()/createDecoder()`), useful for transcoding or debugging.
- `api.audio` has `AudioConverter` (PCM bytes/shorts conversion).

### 2.3 AudioSender — inject mic audio for a mod-less player
Package `api.audiosender`. Obtain via `VoicechatServerApi.createAudioSender(VoicechatConnection)`.
Then register it with `VoicechatServerApi.registerAudioSender(sender)` (unregister on disconnect).

Interface `AudioSender`:
- `send(byte[] opusEncodedAudioData)` → acts as if the player sent a microphone packet.
  Returns `false` if the player has the real mod installed or the sender is not registered.
- `canSend()` → false if player has the mod or sender unregistered. Check before streaming.
- `reset()` → call when the app stops/pauses talking; resets sequence number and signals
  end-of-stream to clients. Call on PTT release / VAD silence.
- `sequenceNumber(long)` → manual override; normally rely on automatic numbering.
- `whispering(boolean)` / `isWhispering()` → whisper flag passthrough.

Notes:
- `VoicechatConnection` is obtained via `VoicechatServerApi.getConnectionOf(playerUuid)`.
  For Bedrock players (no mod), the connection is "not connected"; use
  `connection.setConnected(true)`-style handling only if API requires — verify at impl time
  whether a fake connection must be flagged. (FLAG: confirm against SVC source; some versions
  require `connection.isInstalled()==false` players to be marked connected for senders to work.)
- One `AudioSender` per Bedrock player session.

### 2.4 PlayerAudioListener — capture everything a player would hear
Package `api.audiolistener`. Obtain builder via `VoicechatServerApi.playerAudioListenerBuilder()`.

`PlayerAudioListener.Builder`:
- `setPlayer(UUID)` or `setPlayer(ServerPlayer)` — required; the player to listen for.
- `setPacketListener(Consumer<SoundPacket>)` — required; receives every `SoundPacket`
  the player would hear.
- `build()` → `PlayerAudioListener`; throws `IllegalStateException` if player/listener unset.
- Register with `VoicechatServerApi.registerAudioListener(listener)`; unregister on disconnect.

`SoundPacket` (package `api.packets`) hierarchy:
- `SoundPacket` → `getSender()` (UUID), `getOpusEncodedData()`, `getCategory()`.
- Subtypes: `LocationalSoundPacket` (has `getPosition()`, `getDistance()`),
  `EntitySoundPacket` (has entity UUID, `isWhispering()`), `StaticSoundPacket` (group audio).
- For the bridge: forward `opusEncodedData` + sender UUID + spatial metadata (position/distance)
  to the app. App can do simple volume attenuation/pan from metadata, or play flat (v1: flat,
  but include metadata in the protocol from day one).

### 2.5 Groups
- Create: `VoicechatServerApi.groupBuilder()` → `Group.Builder` →
  name, password, persistent flag, type (`Group.Type`: NORMAL / OPEN / ISOLATED), `build()`.
- Assign player: `VoicechatConnection.setGroup(group)`.
- Group audio arrives at listeners as `StaticSoundPacket`.
- The app's "room + password" UI maps 1:1 to SVC groups. Let the plugin own group
  creation/lookup; app only sends `{groupName, password}`.

### 2.6 Useful events (package `api.events`)
- `VoicechatServerStartedEvent` — capture `VoicechatServerApi`.
- `PlayerConnectedEvent` / `PlayerDisconnectedEvent` — SVC connection state.
- `JoinGroupEvent` / `LeaveGroupEvent` / `CreateGroupEvent` — sync room state to app.
- `MicrophonePacketEvent` — alternative low-level hook (not needed if using listener API).

---

## 3. Floodgate API Reference (Geyser)

Dependency: `org.geysermc.floodgate:api` (provided by the Floodgate plugin at runtime).
Add `softdepend: [floodgate, voicechat]` in `plugin.yml`.

- `FloodgateApi.getInstance()` — singleton.
- `isFloodgatePlayer(UUID uuid)` → true if Bedrock-via-Geyser.
- `getPlayer(UUID uuid)` → `FloodgatePlayer`:
  - `getXuid()` → String XUID (decimal).
  - `getCorrectUsername()`, `getJavaUniqueId()`.
- Floodgate UUID format: `00000000-0000-0000-XXXX-XXXXXXXXXXXX` where the low 64 bits encode
  the XUID — but always use the API, not string parsing.
- Lookup direction needed by the bridge: **XUID → online Player**. Maintain a
  `Map<String /*xuid*/, UUID /*javaUuid*/>` updated on Bukkit `PlayerJoinEvent`/`PlayerQuitEvent`
  (filtered by `isFloodgatePlayer`).

---

## 4. Xbox Authentication (App → Plugin)

App side (Android, MSAL):
1. MSAL interactive sign-in, scope `XboxLive.signin offline_access`.
2. Exchange MSA access token → Xbox **user token**: `POST https://user.auth.xboxlive.com/user/authenticate`
   with `Properties: {AuthMethod: "RPS", SiteName: "user.auth.xboxlive.com", RpsTicket: "d=<token>"}`.
3. Exchange → **XSTS token**: `POST https://xsts.auth.xboxlive.com/xsts/authorize`
   with `RelyingParty: "http://xboxlive.com"`, `SandboxId: "RETAIL"`.
4. XSTS response `DisplayClaims.xui[0].xid` = **XUID**; `uhs` = user hash.

Plugin/backend side verification:
- App sends `XBL3.0 x=<uhs>;<xsts_token>` in the session-open message.
- Verify by calling an Xbox Live endpoint (e.g. `https://profile.xboxlive.com/users/me/profile/settings`)
  with that header and confirming the returned XUID matches the claimed one.
  (Offline JWT validation of XSTS is not publicly documented; the profile-call check is the
  pragmatic standard.)
- After verification, issue our own short-lived **session JWT** (HS256, contains xuid, exp ≤ 1h).
  All subsequent WS messages / UDP packets authenticate with this, not the XSTS token.

---

## 5. App ↔ Plugin Protocol

Transport: WebSocket (TLS via reverse proxy / Cloudflare optional) for signaling + audio v1.
(v2 option: raw UDP for audio with the session JWT-derived key; keep abstraction ready.)

Signaling messages (JSON, `type` field):
- `auth` {xstsHeader} → `auth_ok` {sessionToken, xuid} | `auth_fail`
- `status` → `status` {inGame: bool, javaUuid?, currentRoom?, groups: [{name, hasPassword, type}]}
- `audio_state` {muted: bool, deafened: bool} — app reports local mute/deafen state
- `join_room` {name, password?, groupType?} → `join_ok` | `join_fail` {reason} — `groupType` is `normal`|`open`|`isolated`, create-only; defaults to `isolated`
- `leave_room` → `leave_ok`
- server-push: `room_changed` {room: string|null} — player's SVC group membership changed (e.g. via Java mod UI)
- server-push: `player_joined_game`, `player_left_game`, `group_update`, `room_changed`, `kicked`
- `ping`/`pong` keepalive (10 s)

Audio frames (binary WS messages):
- Uplink: `[u8 type=0x01][u16 seq][opus payload]`
- Downlink: `[u8 type=0x02][16B senderUuid][u8 flags(whisper|static)][f32 x,y,z]?[f32 distance]?[opus payload]`
- 20 ms cadence; coalescing/jitter buffer is the app's job (target 60–100 ms buffer).

Session state machine (plugin side, per app connection):
`CONNECTED → AUTHED → WAITING_FOR_PLAYER → IN_GAME → IN_ROOM` ;
transitions driven by Bukkit join/quit events and app messages. On app WS close:
unregister AudioSender + AudioListener, leave group, clean maps. On player quit:
push `player_left_game`, drop to WAITING_FOR_PLAYER.

---

## 6. Implementation Plan (execute phase by phase; stop after each phase for review)

### Phase 0 — Scaffolding
- [ ] Paper plugin project (Gradle, Java 21, paper-api, voicechat-api, floodgate-api).
- [ ] `plugin.yml` with softdepends; empty `VoicechatPlugin` registered via `BukkitVoicechatService`.
- [ ] Dev environment: Paper + Geyser + Floodgate + SVC bukkit jar; docker-compose for local run.
- Exit criteria: plugin loads, logs SVC api acquired on `VoicechatServerStartedEvent`.

### Phase 1 — Identity & presence
- [ ] XUID↔player map from join/quit events (Floodgate-filtered).
- [ ] Embedded WebSocket server (Netty or Java-WebSocket) on configurable port.
- [ ] `auth` flow: XSTS verification call, session JWT issue, `status` message.
- Exit criteria: app (or `wscat` script) can auth and see its own in-game status flip on join/quit.

### Phase 2 — Audio downlink (game → app)
- [ ] On IN_GAME: build + register `PlayerAudioListener` for the player UUID.
- [ ] Serialize `SoundPacket` → binary downlink frame; send over WS.
- [ ] Test harness: Java client with SVC mod talks near the Bedrock player; dump received
      Opus to file, decode offline, verify audio.
- Exit criteria: audible, correctly attributed audio captured from proximity + group sources.

### Phase 3 — Audio uplink (app → game)
- [ ] On IN_GAME: `createAudioSender(connection)` + `registerAudioSender`; handle the
      mod-installed conflict (`canSend()==false` → reject with reason).
- [ ] Uplink frames → `sender.send(opus)`; idle >200 ms → `sender.reset()`.
- [ ] Resolve the FLAG in §2.3 (fake-connection requirement) against SVC source.
- Exit criteria: Java player hears the test client's mic audio positioned at the Bedrock player.

### Phase 4 — Rooms (groups)
- [ ] `join_room`/`leave_room` mapped to SVC groups (create-if-missing, password check via SVC).
- [ ] Push `group_update` on Join/Leave/CreateGroupEvent.
- Exit criteria: app user joins a password group and talks with Java group members.

### Phase 5 — Android app
- [ ] Kotlin app: MSAL login → XSTS chain; server IP:port screen; room list + password dialog.
- [ ] Audio: `AudioRecord` (48k mono) → libopus (concentus or JNI) 20 ms frames → WS;
      downlink → jitter buffer → `AudioTrack`. Echo cancellation: `AcousticEchoCanceler`.
- [ ] Foreground service + partial wakelock so audio survives backgrounding; auto-reconnect
      with exponential backoff; auto leave on app kill (server detects WS close).
- Exit criteria: end-to-end flow §steps 1–8 of the product spec works on a real device.

### Phase 6 — Hardening
- [ ] Rate limiting per session (frames/s), max sessions per XUID = 1 (kick older).
- [ ] Metrics/logging (latency, packet loss) — wire into existing OpenObserve stack.
- [ ] Config file: WS port, JWT secret, allowed origins, room defaults.
- [ ] Load test: 20 concurrent synthetic clients streaming 20 ms frames.

---

## 7. Known Risks / Open Questions
- §2.3 FLAG: AudioSender + not-connected `VoicechatConnection` semantics — verify against
  SVC source for the exact installed version before Phase 3.
- WS-over-TCP audio adds head-of-line blocking; acceptable for v1, plan UDP path for v2.
- Geyser/Floodgate API surface can shift between majors — pin versions in Gradle.
- Xbox auth endpoints are unofficial-but-stable; isolate behind an `XboxAuthVerifier` interface.
- iOS later: same protocol; CallKit/AVAudioSession specifics out of scope for v1.