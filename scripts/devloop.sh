#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${GHIDRA_INSTALL_DIR:-}" ]]; then
  echo "GHIDRA_INSTALL_DIR is not set. Export it before running this script." >&2
  exit 1
fi

echo "Building GhidraCopilot continuously (jar + syncJarToLib). Press Ctrl+C to stop."
exec ./gradlew --continuous jar syncJarToLib
