#!/usr/bin/env sh
set -eu

CHANNEL="stable"
JRE_API="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse"
JAR_NAME="solarminer-pc-agent-standalone.jar"

say() { printf '%s\n' "$1"; }
fail() { printf 'Fehler / Error: %s\n' "$1" >&2; exit 1; }

case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64) ;;
    *) fail "Dieses Installationsskript unterstützt derzeit nur Linux x64. / This installer currently supports Linux x64 only." ;;
esac

if [ "${1:-}" = "--channel" ]; then
    [ "$#" -ge 2 ] || fail "Kanal fehlt. / Channel is missing."
    CHANNEL="$2"
    shift 2
fi
case "$CHANNEL" in stable|beta) ;; *) fail "Unbekannter Kanal: $CHANNEL / Unknown channel: $CHANNEL" ;; esac

install_tooling() {
    missing=""
    for tool in curl tar sha256sum; do
        command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"
    done
    [ -z "$missing" ] && return
    say "Installiere benötigte Werkzeuge:$missing / Installing required tools:$missing"
    if command -v sudo >/dev/null 2>&1; then SUDO=sudo; elif [ "$(id -u)" -eq 0 ]; then SUDO=""; else fail "sudo fehlt. / sudo is required."; fi
    if command -v apt-get >/dev/null 2>&1; then
        $SUDO apt-get update
        $SUDO apt-get install -y curl tar coreutils ca-certificates
    elif command -v dnf >/dev/null 2>&1; then
        $SUDO dnf install -y curl tar coreutils ca-certificates
    elif command -v yum >/dev/null 2>&1; then
        $SUDO yum install -y curl tar coreutils ca-certificates
    elif command -v zypper >/dev/null 2>&1; then
        $SUDO zypper --non-interactive install curl tar coreutils ca-certificates
    elif command -v pacman >/dev/null 2>&1; then
        $SUDO pacman -Sy --needed --noconfirm curl tar coreutils ca-certificates
    elif command -v apk >/dev/null 2>&1; then
        $SUDO apk add curl tar coreutils ca-certificates
    else
        fail "Kein unterstützter Paketmanager für:$missing / No supported package manager for:$missing"
    fi
}

install_tooling
if [ "$CHANNEL" = "beta" ]; then
    release_tag="$(curl -fsSL 'https://api.github.com/repos/Solarminer-app/pc-agent/releases?per_page=100' | sed -n 's/.*"tag_name": "\(pc-agent-beta-[^"]*\)".*/\1/p' | head -n 1)"
    [ -n "$release_tag" ] || fail "Kein Beta-Release gefunden. / No beta release found."
    RELEASE_BASE="https://github.com/Solarminer-app/pc-agent/releases/download/$release_tag"
    INSTALL_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/solarminer-pc-agent-beta"
else
    RELEASE_BASE="https://github.com/Solarminer-app/pc-agent/releases/latest/download"
    INSTALL_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/solarminer-pc-agent"
fi
RUNTIME_DIR="$INSTALL_DIR/runtime"
say "Kanal: $CHANNEL / Channel: $CHANNEL"
mkdir -p "$INSTALL_DIR"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

if [ ! -x "$RUNTIME_DIR/bin/java" ]; then
    say "Lade private Java-21-Laufzeit … / Downloading private Java 21 runtime …"
    jre_release_url="$(curl -fsSI "$JRE_API" | awk 'tolower($1) == "location:" {sub(/\r$/, "", $2); print $2; exit}')"
    [ -n "$jre_release_url" ] || fail "Adoptium lieferte keine Download-URL. / Adoptium returned no download URL."
    curl -fL "$JRE_API" -o "$tmp_dir/runtime.tar.gz"
    curl -fL "${jre_release_url}.sha256.txt" -o "$tmp_dir/runtime.sha256"
    expected="$(awk 'NR==1 {print $1}' "$tmp_dir/runtime.sha256")"
    actual="$(sha256sum "$tmp_dir/runtime.tar.gz" | awk '{print $1}')"
    [ "${#expected}" -eq 64 ] || fail "Java-Prüfsumme ist ungültig. / Java checksum is invalid."
    [ "$expected" = "$actual" ] || fail "Java-Prüfsumme stimmt nicht. / Java checksum mismatch."
    mkdir -p "$tmp_dir/runtime-extract"
    tar -xzf "$tmp_dir/runtime.tar.gz" -C "$tmp_dir/runtime-extract"
    runtime_home="$(find "$tmp_dir/runtime-extract" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    [ -x "$runtime_home/bin/java" ] || fail "Java-Archiv ist ungültig. / Java archive is invalid."
    if [ -e "$RUNTIME_DIR" ]; then mv "$RUNTIME_DIR" "$RUNTIME_DIR.incomplete.$(date +%s)"; fi
    mv "$runtime_home" "$RUNTIME_DIR"
fi

say "Lade PC-Agent … / Downloading PC Agent …"
curl -fL "$RELEASE_BASE/$JAR_NAME" -o "$tmp_dir/$JAR_NAME"
curl -fL "$RELEASE_BASE/$JAR_NAME.sha256" -o "$tmp_dir/$JAR_NAME.sha256"
expected="$(awk 'NR==1 {print $1}' "$tmp_dir/$JAR_NAME.sha256")"
actual="$(sha256sum "$tmp_dir/$JAR_NAME" | awk '{print $1}')"
[ "${#expected}" -eq 64 ] || fail "PC-Agent-Prüfsumme ist ungültig. / PC Agent checksum is invalid."
[ "$expected" = "$actual" ] || fail "PC-Agent-Prüfsumme stimmt nicht. / PC Agent checksum mismatch."
mv "$tmp_dir/$JAR_NAME" "$INSTALL_DIR/$JAR_NAME"

say "PC-Agent startet auf http://127.0.0.1:8084/ / PC Agent is starting at http://127.0.0.1:8084/"
cd "$INSTALL_DIR"
exec "$RUNTIME_DIR/bin/java" -jar "$JAR_NAME" --solarminer.agent.standalone=true --server.address=0.0.0.0 "$@"
