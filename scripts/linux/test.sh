#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_BIN="${JAVA_BIN:-java}"

"$ROOT_DIR/scripts/linux/build.sh"

"$JAVA_BIN" \
  -cp "$ROOT_DIR/build/classes/test:$ROOT_DIR/build/classes/main:$ROOT_DIR/src/test/resources:$ROOT_DIR/lib/postgresql-42.7.10.jar" \
  jp.co.nksol.rpssync.RpsTableSyncAppTest
