# SVCGeyser

Bedrock voice-chat bridge for **PaperMC** servers running **GeyserMC**, **Floodgate**, and **Simple Voice Chat (SVC)**.

**Latest release:** [v0.0.1-rc.1](https://github.com/sirilerklab/svcgeyser/releases/tag/v0.0.1-rc.1) — download the plugin JAR and Android APK from [GitHub Releases](https://github.com/sirilerklab/svcgeyser/releases).

![SVCGeyser showcase — Bedrock and Java players in voice chat](docs/images/showcase.webp)

---

## What is this?

SVCGeyser lets **Minecraft Bedrock** players use proximity voice chat on a Java server — the same voice system that **Simple Voice Chat** provides to Java mod users.

Bedrock players install a companion **Android app**. Their microphone audio is injected into SVC on the server, and they hear everything SVC would normally send to a Java mod client: proximity chat, voice groups, and whisper. Java players with the SVC mod and Bedrock players using the app can talk to each other in the same world.

**XUID is the join key.** The app proves identity with a Microsoft / Xbox token. Floodgate maps that XUID to the in-game Bedrock player. Gamertag strings are never trusted.

```
Android app  ── WebSocket (JSON + Opus) ──  SVCGeyser plugin  ── SVC API ──  Java SVC mod clients
 (Kotlin)                                      (Paper)              (UDP)
```

Audio format (fixed by SVC): **Opus, 48 kHz mono, 20 ms frames** (960 samples per packet).

---

## Requirements

| Component | Notes |
|-----------|-------|
| **Paper** 1.21.4 | Java 21 server (built and tested against this version) |
| [**GeyserMC**](https://geysermc.org/) | Required — Bedrock players join the Java server |
| [**Floodgate**](https://wiki.geysermc.org/floodgate/) | Required — exposes Bedrock XUIDs to the plugin |
| [**Simple Voice Chat**](https://modrinth.com/plugin/simple-voice-chat) | Required — Bukkit/Paper plugin ≥ 2.6.x (not Fabric/Forge mod alone) |
| **SVCGeyser plugin** | This repository's Paper plugin |
| **SVCGeyser Android app** | Companion app for Bedrock players (Android 7.0+ / API 24) |

Spigot or other forks may work if SVC and Floodgate load correctly, but only **Paper 1.21.4** is officially supported.

---

## Installation

Download from [v0.0.1-rc.1](https://github.com/sirilerklab/svcgeyser/releases/tag/v0.0.1-rc.1):

- [`svcgeyser-0.0.1-rc.1.jar`](https://github.com/sirilerklab/svcgeyser/releases/download/v0.0.1-rc.1/svcgeyser-0.0.1-rc.1.jar)
- [`svcgeyser-0.0.1-rc.1.apk`](https://github.com/sirilerklab/svcgeyser/releases/download/v0.0.1-rc.1/svcgeyser-0.0.1-rc.1.apk)

### Server plugin

1. Download [`svcgeyser-0.0.1-rc.1.jar`](https://github.com/sirilerklab/svcgeyser/releases/download/v0.0.1-rc.1/svcgeyser-0.0.1-rc.1.jar).
2. Copy into your Paper server's `plugins/` folder (requires GeyserMC, Floodgate, Simple Voice Chat).
3. Restart the server and edit `plugins/SVCGeyser/config.yml`:
   - Set `jwt-secret` to a long random value (never commit this).
   - Confirm `ws-port` (default 9000) is reachable from player phones.

   ```yaml
   ws-port: 9000
   jwt-secret: "change-me-to-a-long-random-secret"
   ```

   On success, the console shows:

   ```
   [SVCGeyser] SVC API acquired — bridge ready
   [SVCGeyser] Bridge WS server listening on port 9000
   ```

### Android app

1. Download [`svcgeyser-0.0.1-rc.1.apk`](https://github.com/sirilerklab/svcgeyser/releases/download/v0.0.1-rc.1/svcgeyser-0.0.1-rc.1.apk).
2. On the device: enable **Install unknown apps** for your browser or files app.
3. Open the APK and install (Android 7.0+ / API 24+).
4. Grant **Microphone** and **Internet**. For the optional bubble overlay, enable **Display over other apps** manually — see [Enable bubble overlay](#enable-display-over-other-apps-bubble-overlay) (required on some phones due to protected access).
5. Sign in with the same Microsoft account used on Bedrock via Geyser.
6. Connect to `ws://<server-ip>:9000` (or your configured port).

### Build locally (maintainers)

- Plugin: `cd plugin && ./gradlew shadowJar -PreleaseVersion=0.0.1-rc.1`
- App: place `Concentus.jar` in `app/app/libs/`, set `liveOAuthClientId` in `app/gradle.local.properties`, then `cd app && ./gradlew assembleRelease`

### Checksums

SHA256 sums are attached as `.sha256` files on the [release page](https://github.com/sirilerklab/svcgeyser/releases/tag/v0.0.1-rc.1).

---

## Usage (players)

After connecting in the app:

1. Join the Minecraft server on **Bedrock** (via Geyser).
2. Wait until the app shows **In game**, then grant **microphone** permission if prompted.
3. **Join or create a voice channel** from the room list. Use mute, deafen, and speaker/earpiece controls as needed.

Voice channels in the app map 1:1 to SVC groups on the server. When joining a Java-created password-protected group, enter the password in the app.

Use your server's public IP or hostname in the app — not `localhost`, unless testing on the same machine. For public internet deployment over TLS, put a reverse proxy in front and use `wss://` (see [Limitations](#limitations)).

---

## Enable "Display over other apps" (bubble overlay)

This permission is **optional** — voice chat works without it. Enable it only if you want the floating bubble on the **Rooms** screen (tap the **mic icon** in the top bar) to control mute, deafen, and channels while Minecraft is in the foreground.

> [!IMPORTANT]
> **Why do I need to enable this manually?**
>
> Many phones **protect "Display over other apps"** for security. Because SVCGeyser is installed from a GitHub APK (not Google Play), Android may block the permission until you approve **restricted settings** and turn it on yourself in system Settings. This is normal — follow the steps below for your phone brand (Google Pixel, Samsung, Xiaomi, OPPO, vivo).

### Step 1: Try from the app (all phones)

1. Connect in the app and open the **Rooms** screen.
2. Tap the **mic icon** in the top bar.
3. Android opens the overlay settings for **SVCGeyser**. Turn **Allow display over other apps** on.

If you see **Restricted setting** or **For your security, this setting is currently unavailable**, continue to [Step 2](#step-2-allow-restricted-settings-sideloaded-apk).

### Step 2: Allow restricted settings (sideloaded APK)

Do this once before overlay can be enabled:

1. Open **Settings**.
2. Go to **Apps** (or **App management** on some phones).
3. Tap **SVCGeyser**.
4. Tap the **⋮** menu (top-right) → **Allow restricted settings**.
5. Confirm with your PIN, pattern, or fingerprint.

If the **⋮** menu does not show **Allow restricted settings**:

- Tap the mic icon in SVCGeyser first so Android records the blocked request.
- Long-press the SVCGeyser app icon → **App info** → try the **⋮** menu again.

Then return to the app and tap the mic icon again (Step 1).

### Step 3: Enable overlay by phone brand

Menu names differ by Android version. Use the **Settings search bar** and try: `Display over other apps`, `Appear on top`, `Draw over other apps`, or `Floating window`.

#### Google Pixel (stock Android)

**Settings → Apps → See all apps → SVCGeyser → Allow display over other apps → On**

Or: **Settings → Apps → Special app access → Display over other apps → SVCGeyser → Allow**

#### Samsung (One UI)

**Settings → Apps → ⋮ (top-right) → Special access → Appear on top → SVCGeyser → On**

Samsung calls this permission **Appear on top**, not "Display over other apps".

#### Xiaomi / Redmi / POCO (MIUI / HyperOS)

**Settings → Privacy protection → Special permissions → Display over other apps → SVCGeyser → Allow**

On some models: **Settings → Apps → Manage apps → SVCGeyser → Other permissions → Display pop-up windows while running in the background → Allow**

If the bubble disappears in-game, also open **Settings → Battery → SVCGeyser** and set battery use to **No restrictions** (or disable battery optimization for the app).

#### OPPO / Realme (ColorOS)

**Settings → App management → Display over other apps → SVCGeyser → Allow**

On newer ColorOS: **Settings → App management → Special app access → Display over other apps → SVCGeyser → Allow**

#### vivo (Funtouch OS)

**Settings → Apps & permissions → Special app permissions → Display over other apps → SVCGeyser → Allow**

On some models the option is named **Display pop-up windows** or **Floating window** (**Settings → More settings → Permission management → Floating window**).

If the bubble still does not show, disable background restrictions: **Settings → Battery → Background power consumption management → SVCGeyser → Allow high background power consumption** (wording varies).

### Still not working?

| Problem | What to try |
|---------|-------------|
| Toggle is greyed out | Complete [Step 2](#step-2-allow-restricted-settings-sideloaded-apk) first |
| Option not found | Use Settings search; paths change between OS updates |
| Bubble shows but stops in Minecraft | Disable battery optimization / background limits for SVCGeyser |
| You do not need the bubble | Use the full SVCGeyser app from the app switcher — voice still works |

---

## Limitations

| Limitation | Details |
|------------|---------|
| **Android only** | No iOS companion app yet. |
| **Plain WebSocket** | App uses `ws://` only. `wss://` requires an external reverse proxy. |
| **Paper 1.21.4** | Other server versions are untested. |
| **SVC mod on Bedrock client** | If a Bedrock player also has the SVC mod installed, bridge uplink is rejected (`uplink_rejected — mod installed`). |
| **Java-created group types** | Groups created in-game via the SVC mod UI use whatever type the Java player picks. Only **Isolated** fully blocks outside proximity audio. The app defaults to Isolated when creating channels. |
| **Group password reflection** | Password checks for Java-created protected groups rely on SVC internal fields (v2.6.13). A future SVC update may break this until the plugin is updated. |
| **Manual Concentus setup** | Only required when building the app from source — not needed for the release APK. |
| **Rate limiting & metrics** | Not implemented yet (planned Phase 6). |

---

## Screenshots

### Server connect & room list

![App overview — server list and voice channels](docs/images/screen-overview.webp)

### Join a voice channel

![Joining a voice channel with password](docs/images/screen-group-join.webp)

### Create a channel

![Create channel dialog with group type selection](docs/images/create-group.webp)

---

## Troubleshooting

| Symptom | What to check |
|---------|---------------|
| Stuck on **Waiting for player** | Bedrock player must be online on the same server with the **same Microsoft account** used in the app. Geyser + Floodgate must be running. |
| Java players can't hear Bedrock | Look for `Audio sender registered` in console. If you see `Uplink frame dropped`, SVC sender never registered. |
| Bedrock can't hear Java | Look for `Audio listener registered`. Check mic permission and mute/deafen in the app. |
| WebSocket fails | Verify `ws-port` is open and reachable from the phone. Confirm `jwt-secret` is set. |
| Bubble overlay blocked | See [Enable "Display over other apps"](#enable-display-over-other-apps-bubble-overlay) — allow restricted settings, then enable overlay for your phone brand. |

Full protocol and design details: [`docs/DOCUMENT.md`](docs/DOCUMENT.md) · [`docs/SUMMARY.md`](docs/SUMMARY.md)

---

## Development

```
SVCGeyser/
├── plugin/          # Paper plugin (Java 21, Gradle)
├── app/             # Android companion (Kotlin, Jetpack Compose)
├── docs/            # Design doc, images, protocol spec
└── test/            # TypeScript WebSocket test harness (Bun)
```

Releases are published via the GitHub Actions **Release** workflow (`.github/workflows/release.yml`).

```bash
cd plugin && ./gradlew shadowJar    # plugin jar
cd app    && ./gradlew assembleDebug # debug APK
cd app    && ./gradlew test         # unit tests
```

---

## License & credits

- **Simple Voice Chat** — [maxhenkel](https://github.com/MaxHenkel) / [modrepo.de API](https://voicechat.modrepo.de)
- **GeyserMC / Floodgate** — [GeyserMC](https://github.com/GeyserMC)
- **Concentus** — pure-Java Opus ([lostromb/concentus](https://github.com/lostromb/concentus))
