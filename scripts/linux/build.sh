#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$ROOT_DIR/build"
MAIN_OUT="$BUILD_DIR/classes/main"
TEST_OUT="$BUILD_DIR/classes/test"
DIST_JAR="$ROOT_DIR/dist/rps-table-sync.jar"
PGJDBC_JAR="$ROOT_DIR/lib/postgresql-42.7.10.jar"

rm -rf "$MAIN_OUT" "$TEST_OUT"
mkdir -p "$MAIN_OUT" "$TEST_OUT" "$ROOT_DIR/dist"

MAIN_SOURCES=()
while IFS= read -r source; do
  MAIN_SOURCES+=("$source")
done < <(find "$ROOT_DIR/src/main/java" -name '*.java' | sort)

javac -encoding UTF-8 -cp "$PGJDBC_JAR" -d "$MAIN_OUT" "${MAIN_SOURCES[@]}"

jar --create --file "$DIST_JAR" -C "$MAIN_OUT" .

TEST_SOURCES=()
while IFS= read -r source; do
  TEST_SOURCES+=("$source")
done < <(find "$ROOT_DIR/src/test/java" -name '*.java' | sort)

javac -encoding UTF-8 -cp "$MAIN_OUT:$PGJDBC_JAR" -d "$TEST_OUT" "${TEST_SOURCES[@]}"

echo "Built $DIST_JAR"
