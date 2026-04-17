#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CONFIG_FILE="${1:-$ROOT_DIR/config/rps-sync.properties}"
DIST_JAR="$ROOT_DIR/dist/rps-table-sync.jar"
PGJDBC_JAR="$ROOT_DIR/lib/postgresql-42.7.10.jar"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Config file not found: $CONFIG_FILE" >&2
  exit 1
fi

if [ ! -f "$DIST_JAR" ]; then
  echo "Application jar not found: $DIST_JAR" >&2
  echo "Run scripts/linux/build.sh first, or use the jar committed in this repository." >&2
  exit 1
fi

if [ -n "${JAVA_BIN:-}" ]; then
  JAVA_CMD="$JAVA_BIN"
else
  RUNTIME_DIR="$("$ROOT_DIR/scripts/linux/setup-bundled-runtime.sh")"
  JAVA_CMD="$RUNTIME_DIR/bin/java"
fi

exec "$JAVA_CMD" -cp "$DIST_JAR:$PGJDBC_JAR" jp.co.nksol.rpssync.RpsTableSyncApp "$CONFIG_FILE"
