#!/usr/bin/env bash
# Build the SVCGeyser Paper plugin (shadow JAR).
#
# Usage:
#   ./scripts/build-plugin.sh                  # builds svcgeyser-1.0-SNAPSHOT.jar
#   ./scripts/build-plugin.sh 0.1.1            # builds svcgeyser-0.1.1.jar
#   PLUGIN_VERSION=0.1.1 ./scripts/build-plugin.sh
#   ./scripts/build-plugin.sh --clean          # clean + default version
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_DIR="$ROOT/plugin"
VERSION="${PLUGIN_VERSION:-1.0-SNAPSHOT}"
GRADLE_ARGS=("$@")

if [ $# -gt 0 ] && [[ "$1" != --* ]]; then
  VERSION="$1"
  shift
  GRADLE_ARGS=("$@")
fi

echo "Building plugin (version: $VERSION)…"
cd "$PLUGIN_DIR"
./gradlew shadowJar -PreleaseVersion="$VERSION" "${GRADLE_ARGS[@]}"

JAR="$PLUGIN_DIR/build/libs/svcgeyser-${VERSION}.jar"
echo ""
echo "Build complete: $JAR"
echo "Install: copy to your server's plugins/ folder and restart"
