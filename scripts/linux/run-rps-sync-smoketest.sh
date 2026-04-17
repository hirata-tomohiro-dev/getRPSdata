#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BASE_CONFIG="${1:-$ROOT_DIR/config/rps-sync.properties}"
TABLE_LIMIT="${2:-5}"
TEMP_CONFIG="$(mktemp "$ROOT_DIR/config/rps-sync-smoketest.XXXXXX.properties")"

if [ ! -f "$BASE_CONFIG" ]; then
  echo "Base config file not found: $BASE_CONFIG" >&2
  exit 1
fi

case "$TABLE_LIMIT" in
  ''|*[!0-9]*)
    echo "TABLE_LIMIT must be a positive integer." >&2
    exit 1
    ;;
esac

if [ "$TABLE_LIMIT" -le 0 ]; then
  echo "TABLE_LIMIT must be greater than zero." >&2
  exit 1
fi

cleanup() {
  rm -f "$TEMP_CONFIG"
}
trap cleanup EXIT

cat "$BASE_CONFIG" > "$TEMP_CONFIG"
cat >> "$TEMP_CONFIG" <<EOF

# Auto-generated smoke test overrides
sync.maxTables=$TABLE_LIMIT
result.dir=result-smoketest
pg.table=rps_table_inventory_smoketest
EOF

"$ROOT_DIR/scripts/linux/run-rps-sync.sh" "$TEMP_CONFIG"
