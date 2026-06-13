# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Bedrock voice-chat bridge. Goal: let Minecraft **Bedrock** players (connected via GeyserMC/Floodgate) use voice chat through a companion Android app, bridged into **Simple Voice Chat (SVC)** on the Java/Paper server. End state: Bedrock app users and Java SVC-mod users hear each other with proximity, groups, and whisper all handled by SVC.

`docs/DOCUMENT.md` is the authoritative design + implementation plan. **Read it before doing substantive work** — it contains the SVC API reference (v2.6.x), Floodgate API usage, the Xbox auth chain, the app↔plugin wire protocol, and a phased build plan. The key invariant: **XUID is the join key** — the app proves its XUID via an Xbox XSTS token; Floodgate exposes the XUID of online Bedrock players; never trust gamertag strings.

## Current state

Both modules are **feature-complete through Phase 5** (audio):

**Plugin (`plugin/`) — Phases 0–4 done:**
- Gradle + Shadow JAR build; SVC `VoicechatPlugin` registration, group events, audio routing
- Floodgate XUID map, JWT session tokens, Xbox XSTS auth verifier
- `BridgeServer` WebSocket (Java-WebSocket 1.5.7, relocated); `AppSession` state machine; `GroupManager`
- Binary wire protocol: uplink `[0x01][u16 seq][opus]`, downlink `[0x02][16B uuid][u8 flags][spatial?][opus]`
- `AudioFrameSerializer` for `SoundPacket` → binary downlink frames
- `plugin.yml` already has `softdepend: [floodgate, voicechat]`

**App (`app/`) — Phases 0–5 done:**
- Auth: login.live.com OAuth2 PKCE via Chrome Custom Tabs (`LiveOAuthHelper`) → Xbox user token → XSTS (`XboxAuthHelper`). **MSAL was removed** — no Azure Partner Program needed.
- Network: OkHttp WebSocket `BridgeClient`; full JSON signaling + binary downlink; sealed `InboundMessage`
- ViewModel: `AppViewModel` with auto-reconnect (exponential backoff 1 s–30 s, generation counter)
- Screens: `LoginScreen` → `ServerConnectScreen` → `RoomListScreen` via Navigation Compose; bubble overlay (`BubbleService`/`BubbleController`)
- Audio: `AudioEngine` — AudioRecord 48 kHz → Concentus Opus encode → uplink; downlink → decode → AudioTrack; AEC; 12-frame jitter buffer
- `VoiceService` foreground service (`foregroundServiceType="microphone"`), partial WakeLock

**Blocker before first app build:** place `Concentus.jar` at `app/app/libs/` (download from the [Concentus Java v1.0 release](https://github.com/lostromb/concentus/releases/tag/v1.0-java)).

**Not started:** Phase 6 (rate limiting, metrics), wss:// for public deployment.

When implementing, follow the phased plan in `docs/DOCUMENT.md` and stop at each phase's exit criteria for review.

## Two independent modules, two build systems

This repo is **not** a single Gradle/Maven project — the two halves build separately and there is no git repo initialized at the root.

### `plugin/` — Paper server plugin (Java, Gradle)
- Build: `cd plugin && ./gradlew shadowJar` → fat jar in `plugin/build/libs/`. (`pom.xml` is a leftover scaffold — the active build is `build.gradle.kts`.)
- Java **21** (`sourceCompatibility/targetCompatibility = VERSION_21` in `build.gradle.kts`).
- Key deps (all declared in `build.gradle.kts`): `paper-api` (compileOnly), `voicechat-api 2.6.13` (compileOnly), `floodgate-api` (compileOnly), `Java-WebSocket 1.5.7` (shaded, relocated to `com.sirilerklab.svcgeyser.libs.ws`).
- Plugin metadata: `plugin/src/main/resources/plugin.yml` (already has `softdepend: [floodgate, voicechat]`).
- Entry point: `com.sirilerklab.svcgeyser.Main extends JavaPlugin`.

### `app/` — Android companion app (Kotlin, Gradle, Jetpack Compose)
- All Gradle commands run from `app/` using the wrapper there:
  - Build debug APK: `cd app && ./gradlew assembleDebug`
  - Unit tests (JVM): `./gradlew test` — single test: `./gradlew test --tests "com.sirilerklab.svcgeyser.ExampleUnitTest"`
  - Instrumented tests (needs device/emulator): `./gradlew connectedAndroidTest`
  - Lint: `./gradlew lint`
- Dependency versions are centralized in `app/gradle/libs.versions.toml` (version catalog) — add/upgrade deps there, reference them as `libs.*` in `app/app/build.gradle.kts`, not as hardcoded coordinates.
- `app/local.properties` (SDK path) is machine-local and untracked.
- minSdk 24, targetSdk/compileSdk 36. UI is 100% Jetpack Compose (Material 3); there are no XML layouts.
- Entry point: `MainActivity` (`app/app/src/main/java/.../MainActivity.kt`); theme in `ui/theme/`.

## Conventions

- Shared package namespace across both modules: `com.sirilerklab.svcgeyser`.
- Audio everywhere is **Opus, 48 kHz mono, 20 ms frames (960 samples / 1920 bytes PCM per packet)** — this format is dictated by SVC and must match on both the app encode and decode paths.
- The app↔plugin protocol (JSON signaling + binary audio frames over WebSocket) is fully specified in `docs/DOCUMENT.md §5`; keep the implementation in sync with that spec, including the per-connection session state machine (`CONNECTED → AUTHED → WAITING_FOR_PLAYER → IN_GAME → IN_ROOM`).
- Open questions / things to verify against live SVC source before relying on them are marked `FLAG:` in the design doc (notably the AudioSender + not-connected `VoicechatConnection` semantics in §2.3).
