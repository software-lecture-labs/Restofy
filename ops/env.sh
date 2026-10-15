#!/usr/bin/env sh
set -eu
umask 077

# Selects a persistent ENV copy; does not run Docker.
ROOT="$(CDPATH= cd "$(dirname "$0")/.." && pwd)"
COMMAND="${1:-status}"
PROFILE="${2:-}"
FORCE="${3:-}"
MARKER='# RESTOFY-ACTIVE-PROFILE='
ACTIVE="$ROOT/.env"

error() { printf '%s\n' "[ERROR] $1" >&2; exit 1; }
usage() { error 'Usage: ./ops/env.sh status | use dev|prod [--force]'; }
[ "$#" -le 3 ] || usage
case "$COMMAND" in status|use) ;; *) usage ;; esac
if [ "$COMMAND" = status ]; then
    [ -z "$PROFILE$FORCE" ] || usage
else
    case "$PROFILE" in dev|prod) ;; *) usage ;; esac
    case "$FORCE" in ''|--force) ;; *) usage ;; esac
fi

RECORDED=''
if [ -f "$ACTIVE" ]; then
    FIRST="$(head -n 1 "$ACTIVE" | tr -d '\r')"
    case "$FIRST" in
        "$MARKER"dev) RECORDED=dev ;;
        "$MARKER"prod) RECORDED=prod ;;
    esac
fi

in_sync() {
    [ -n "$RECORDED" ] && [ -f "$ROOT/.env.$RECORDED" ] || return 1
    BODY="$(sed '1d' "$ACTIVE" | tr -d '\r')"
    SOURCE_BODY="$(tr -d '\r' < "$ROOT/.env.$RECORDED")"
    [ "$BODY" = "$SOURCE_BODY" ]
}

if [ "$COMMAND" = status ]; then
    if [ ! -f "$ACTIVE" ]; then
        printf '%s\n' 'No .env yet. Run: ./ops/env.sh use dev'
    elif [ -z "$RECORDED" ]; then
        printf '%s\n' '.env has no profile marker (not written by this script).'
    elif in_sync; then
        printf '%s\n' "Active profile: $RECORDED (.env matches .env.$RECORDED)"
    else
        printf '%s\n' "Active profile: $RECORDED (.env differs from .env.$RECORDED or the profile file is missing)"
    fi
    exit 0
fi

SOURCE="$ROOT/.env.$PROFILE"
[ -f "$SOURCE" ] || error ".env.$PROFILE not found."
if [ -f "$ACTIVE" ] && ! in_sync; then
    [ "$FORCE" = --force ] || error '.env differs from its profile file. Move edits into .env.dev / .env.prod, or use --force to back up .env to .env.bak before switching.'
    cp "$ACTIVE" "$ROOT/.env.bak"
    printf '%s\n' 'Backed up the current .env to .env.bak'
fi

TEMP_ENV="$(mktemp "$ROOT/.env.switch.XXXXXX")"
trap 'rm -f "$TEMP_ENV"' EXIT HUP INT TERM
{
    printf '%s%s\n' "$MARKER" "$PROFILE"
    cat "$SOURCE"
} > "$TEMP_ENV"
mv "$TEMP_ENV" "$ACTIVE"
printf '%s\n' "Active profile: $PROFILE (.env written from .env.$PROFILE)"
printf '%s\n' 'Restart running applications to apply the change: docker compose up --build -d'
