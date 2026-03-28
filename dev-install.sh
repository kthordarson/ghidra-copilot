#!/usr/bin/env bash
# dev-install.sh — Build, install, and relaunch Ghidra with the Copilot extension.
# Usage: ./dev-install.sh
#
# Reads configuration from .env if present (cp .env.example .env to get started).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Source .env if it exists (env vars set before running override .env values)
if [ -f "$SCRIPT_DIR/.env" ]; then
  set -a
  # shellcheck source=/dev/null
  source "$SCRIPT_DIR/.env"
  set +a
fi

GHIDRA_INSTALL_DIR="${GHIDRA_INSTALL_DIR:-/opt/homebrew/Cellar/ghidra/12.0.4/libexec}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home}"
EXT_DIR="${GHIDRA_INSTALL_DIR}/Ghidra/Extensions"
EXT_NAME="ghidra-copilot"

export JAVA_HOME GHIDRA_INSTALL_DIR

# 1. Kill running Ghidra (if any)
if pgrep -f "ghidra.GhidraRun" > /dev/null 2>&1; then
  echo "⏹  Stopping Ghidra..."
  pkill -f "ghidra.GhidraRun" || true
  sleep 2
fi

# 2. Build
echo "🔨 Building extension..."
cd "$SCRIPT_DIR"
./gradlew buildExtension --quiet

# 3. Find the built ZIP
ZIP=$(ls -t dist/*.zip 2>/dev/null | head -1)
if [ -z "$ZIP" ]; then
  echo "❌ Build produced no ZIP in dist/"
  exit 1
fi
echo "📦 Built: $ZIP"

# 4. Remove old install, unzip new one
echo "📂 Installing to $EXT_DIR/$EXT_NAME ..."
rm -rf "${EXT_DIR:?}/${EXT_NAME}"
mkdir -p "$EXT_DIR"
unzip -q -o "$ZIP" -d "$EXT_DIR"

# 5. Relaunch Ghidra
echo "🚀 Launching Ghidra..."
"$GHIDRA_INSTALL_DIR/ghidraRun" &
disown

echo "✅ Done — Ghidra is starting with the updated extension."
