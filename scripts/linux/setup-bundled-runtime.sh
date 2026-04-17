#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ASSET_TAR="$ROOT_DIR/assets/java/linux-x64/OpenJDK17U-jre_x64_linux_hotspot_17.0.18_8.tar.gz"
TARGET_DIR="$ROOT_DIR/.runtime/temurin17-linux-x64"

if [ -x "$TARGET_DIR/bin/java" ]; then
  echo "$TARGET_DIR"
  exit 0
fi

mkdir -p "$TARGET_DIR"
tar -xzf "$ASSET_TAR" --strip-components=1 -C "$TARGET_DIR"
echo "$TARGET_DIR"
