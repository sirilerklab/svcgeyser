#!/usr/bin/env bash
# Build the SVCGeyser Android debug APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT/app"
CONCENTUS_JAR="$APP_DIR/app/libs/Concentus.jar"
CONCENTUS_URL="https://repo1.maven.org/maven2/io/github/jaredmdobson/concentus/1.0.2/concentus-1.0.2.jar"
APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$CONCENTUS_JAR" ]; then
  echo "Downloading Concentus.jar…"
  mkdir -p "$(dirname "$CONCENTUS_JAR")"
  curl -fsSL -o "$CONCENTUS_JAR" "$CONCENTUS_URL"
fi

echo "Building debug APK…"
cd "$APP_DIR"
./gradlew assembleDebug "$@"

echo ""
echo "Build complete: $APK"

# Run script to install APK
adb install -r "$APK"