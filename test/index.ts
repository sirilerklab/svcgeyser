// test-client.ts
// Bun WS test client for SVCGeyser plugin (replaces wscat for binary audio frames)
//
// Usage:
//   bun run test-client.ts ws://<server-ip>:<ws-port> [--xuid <xuid>] [--dev-token <token>]
//
// What it does:
//   - Connects, sends `auth` (dev-bypass mode by default — adjust to match plugin impl)
//   - Logs all JSON messages (auth_ok, status, player_joined_game, player_left_game, group_update, kicked, ...)
//   - On binary messages: checks first byte
//       0x02 = downlink audio frame -> strips 18-byte header, buffers raw Opus payload
//   - On exit (Ctrl+C) or on `player_left_game`, decodes buffered Opus frames -> PCM -> writes out.wav
//
// Requires `opusscript` for decoding (pure JS, no native build headaches with Bun):
//   bun add opusscript

import OpusScript from "opusscript";

const SERVER_URL = process.argv[2] ?? "ws://127.0.0.1:9000";

// crude arg parsing
const args = process.argv.slice(3);
function getArg(flag: string): string | undefined {
  const i = args.indexOf(flag);
  return i >= 0 ? args[i + 1] : undefined;
}
const DEV_TOKEN = getArg("--dev-token") ?? "dev";
const XUID = getArg("--xuid") ?? "0000000000000000";

// --- Audio config (per DOCUMENT.md §2.2) ---
const SAMPLE_RATE = 48000;
const CHANNELS = 1;
const FRAME_SAMPLES = 960; // 20ms @ 48kHz

// --- Downlink frame header (per DOCUMENT.md §5) ---
// [u8 type=0x02][16B senderUuid][u8 flags(whisper|static)][f32 x,y,z]?[f32 distance]?[opus payload]
// Minimal/no-position header = 18 bytes total (type + uuid + flags).
// If your plugin always includes position+distance (extra 16 bytes), change HEADER_LEN to 34.
const HEADER_LEN = 18;

const opus = new OpusScript(SAMPLE_RATE, CHANNELS, OpusScript.Application.VOIP);

const pcmChunks: Buffer[] = [];
let framesReceived = 0;

console.log(`Connecting to ${SERVER_URL} ...`);
const ws = new WebSocket(SERVER_URL);

ws.onopen = () => {
  console.log("WS open. Sending auth...");
  send({ type: "auth", xstsHeader: `XBL3.0 x=${XUID};${DEV_TOKEN}` });
};

ws.onmessage = (ev) => {
  if (typeof ev.data === "string") {
    handleJson(ev.data);
    return;
  }
  console.log("Binary message received:", ev.data);
  handleBinary(ev.data as ArrayBuffer);
};

ws.onclose = (ev) => {
  console.log(`WS closed (code=${ev.code}, reason=${ev.reason || "-"})`);
  finish();
};

ws.onerror = (err) => {
  console.error("WS error:", err);
};

function send(obj: unknown) {
  const json = JSON.stringify(obj);
  console.log(">>", json);
  ws.send(json);
}

function handleJson(raw: string) {
  let msg: any;
  try {
    msg = JSON.parse(raw);
  } catch {
    console.log("<< [non-JSON text]", raw);
    return;
  }
  console.log("<<", msg);

  switch (msg.type) {
    case "auth_ok":
      console.log(`Authed. session=${msg.sessionToken ?? "(none)"} xuid=${msg.xuid}`);
      send({ type: "status" });
      break;
    case "auth_fail":
      console.error("Auth failed:", msg);
      break;
    case "status":
      console.log(`Status: inGame=${msg.inGame} javaUuid=${msg.javaUuid ?? "-"} groups=${JSON.stringify(msg.groups)}`);
      break;
    case "player_joined_game":
      console.log("✅ player_joined_game", msg);
      break;
    case "player_left_game":
      console.log("👋 player_left_game", msg);
      console.log(`Frames received so far: ${framesReceived}. Writing wav and exiting...`);
      finish();
      break;
    case "group_update":
      console.log("👥 group_update", msg);
      break;
    case "kicked":
      console.warn("⚠️ kicked", msg);
      break;
    case "pong":
      break;
    default:
      console.log("(unhandled type)", msg);
  }
}

function handleBinary(data: ArrayBuffer) {
  const buf = Buffer.from(data);
  if (buf.length === 0) return;

  const type = buf[0];

  if (type !== 0x02) {
    console.log(`<< [binary] type=0x${type.toString(16)} len=${buf.length} (ignored)`);
    return;
  }

  if (buf.length <= HEADER_LEN) {
    console.warn(`<< [audio] frame too short (${buf.length} bytes), skipping`);
    return;
  }

  const senderUuid = buf.subarray(1, 17).toString("hex");
  const flags = buf[17];
  const opusPayload = buf.subarray(HEADER_LEN);

  framesReceived++;
  if (framesReceived === 1 || framesReceived % 50 === 0) {
    console.log(
      `<< [audio] frame #${framesReceived} sender=${senderUuid} flags=0x${flags
        .toString(16)
        .padStart(2, "0")} opusLen=${opusPayload.length}`
    );
  }

  try {
    const pcm: Buffer = opus.decode(opusPayload); // returns Int16 PCM as Buffer
    pcmChunks.push(pcm);
  } catch (err) {
    console.warn(`Opus decode failed on frame #${framesReceived}:`, (err as Error).message);
  }
}

function finish() {
  if (pcmChunks.length === 0) {
    console.log("No audio frames decoded — nothing to write.");
    opus.delete();
    process.exit(0);
  }

  const pcm = Buffer.concat(pcmChunks);
  const wav = pcmToWav(pcm, SAMPLE_RATE, CHANNELS);
  const outPath = "out.wav";
  Bun.write(outPath, wav);
  console.log(`Wrote ${outPath} (${pcm.length} bytes PCM, ${framesReceived} frames, ~${(
    pcm.length /
    (SAMPLE_RATE * CHANNELS * 2)
  ).toFixed(2)}s)`);

  opus.delete();
  process.exit(0);
}

// Minimal WAV (PCM16) header writer
function pcmToWav(pcmData: Buffer, sampleRate: number, channels: number): Buffer {
  const byteRate = sampleRate * channels * 2;
  const blockAlign = channels * 2;
  const dataSize = pcmData.length;

  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + dataSize, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16); // fmt chunk size
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(16, 34); // bits per sample
  header.write("data", 36);
  header.writeUInt32LE(dataSize, 40);

  return Buffer.concat([header, pcmData]);
}

// Graceful Ctrl+C
process.on("SIGINT", () => {
  console.log("\nInterrupted. Finishing up...");
  finish();
});

// Keepalive ping every 10s (per DOCUMENT.md §5)
setInterval(() => {
  if (ws.readyState === WebSocket.OPEN) send({ type: "ping" });
}, 10000);