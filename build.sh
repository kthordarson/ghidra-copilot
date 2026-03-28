#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export GHIDRA_INSTALL_DIR="/opt/homebrew/opt/ghidra/libexec"

./gradlew buildExtension "$@"
