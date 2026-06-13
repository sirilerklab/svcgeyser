# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Bedrock voice-chat bridge. Goal: let Minecraft **Bedrock** players (connected via GeyserMC/Floodgate) use voice chat through a companion Android app, bridged into **Simple Voice Chat (SVC)** on the Java/Paper server. End state: Bedrock app users and Java SVC-mod users hear each other with proximity, groups, and whisper all handled by SVC.

`docs/DOCUMENT.md` is the authoritative design + implementation plan. **Read it before doing substantive work** — it contains the SVC API reference (v2.6.x), Floodgate API usage, the Xbox auth chain, the app↔plugin wire protocol, and a phased build plan. The key invariant: **XUID is the join key** — the app proves its XUID via an Xbox XSTS token; Floodgate exposes the XUID of online Bedrock players; never trust gamertag strings.

## Current state

Both modules are **scaffolds**, roughly Phase 0 of the plan in `docs/DOCUMENT.md`:
- The plugin (`plugin/`) is a bare Paper plugin that only greets players on join. No SVC/Floodgate integration, WebSocket server, or auth yet.
- The app (`app/`) is the default Android Studio Compose template (a "Hello Android" greeting). No login, networking, or audio yet.
- `pom.xml` does not yet declare the `voicechat-api`, `floodgate-api`, or a WebSocket dependency that the design requires.

When implementing, follow the phased plan and stop at each phase's exit criteria for review.

## Two independent modules, two build systems

This repo is **not** a single Gradle/Maven project — the two halves build separately and there is no git repo initialized at the root.

### `plugin/` — Paper server plugin (Java, Maven)
- Build: `cd plugin && mvn package` → jar in `plugin/target/`.
- Java **25** (`maven.compiler.source/target = 25` in `pom.xml`). Note this is ahead of the app's toolchain.
- `paper-api` is a `provided` dependency from the PaperMC repo.
- Plugin metadata: `plugin/src/main/resources/plugin.yml`. Per the design, this needs `softdepend: [floodgate, voicechat]` once those integrations land.
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
