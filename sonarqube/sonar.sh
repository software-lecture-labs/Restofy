#!/usr/bin/env sh
set -eu
if ! command -v node >/dev/null 2>&1; then
    echo '[ERROR] Node.js is missing.' >&2
    exit 1
fi
SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
exec node "$SCRIPT_DIR/sonar.mjs" "$@"
