#!/usr/bin/env sh
set -eu

CHANNEL="stable"
GPU="none"

say() { printf '%s\n' "$1"; }
fail() { printf 'Fehler / Error: %s\n' "$1" >&2; exit 1; }
usage() { say "Nutzung / Usage: $0 [--channel stable|beta] [--gpu none|nvidia|amd]"; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        --gpu) [ "$#" -ge 2 ] || { usage; exit 2; }; GPU="$2"; shift 2 ;;
        --channel) [ "$#" -ge 2 ] || { usage; exit 2; }; CHANNEL="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage; fail "Unbekannte Option: $1 / Unknown option: $1" ;;
    esac
done
case "$GPU" in none|nvidia|amd) ;; *) usage; fail "Unbekannte GPU: $GPU / Unknown GPU: $GPU" ;; esac
case "$CHANNEL" in stable|beta) ;; *) usage; fail "Unbekannter Kanal: $CHANNEL / Unknown channel: $CHANNEL" ;; esac
case "$(uname -s):$(uname -m)" in Linux:x86_64|Linux:amd64) ;; *) fail "Das Docker-Image unterstützt derzeit nur Linux x64. / The Docker image currently supports Linux x64 only." ;; esac

if ! command -v curl >/dev/null 2>&1; then fail "curl wird zum Laden des Installers benötigt. / curl is required to download the installer."; fi

if [ "$CHANNEL" = "beta" ]; then
    release_tag="$(curl -fsSL 'https://api.github.com/repos/Solarminer-app/pc-agent/releases?per_page=100' | sed -n 's/.*"tag_name": "\(pc-agent-beta-[^"]*\)".*/\1/p' | head -n 1)"
    [ -n "$release_tag" ] || fail "Kein Beta-Release gefunden. / No beta release found."
    RELEASE_BASE="https://github.com/Solarminer-app/pc-agent/releases/download/$release_tag"
    INSTALL_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/solarminer-pc-agent-docker-beta"
    export PC_AGENT_IMAGE="verdox/solar-miner-pc-agent:latest-beta"
else
    RELEASE_BASE="https://github.com/Solarminer-app/pc-agent/releases/latest/download"
    INSTALL_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/solarminer-pc-agent-docker"
    export PC_AGENT_IMAGE="verdox/solar-miner-pc-agent:latest"
fi
say "Kanal: $CHANNEL / Channel: $CHANNEL"

install_docker() {
    command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1 && return
    say "Installiere Docker und Compose … / Installing Docker and Compose …"
    if command -v sudo >/dev/null 2>&1; then SUDO=sudo; elif [ "$(id -u)" -eq 0 ]; then SUDO=""; else fail "sudo fehlt. / sudo is required."; fi
    if command -v apt-get >/dev/null 2>&1; then
        $SUDO apt-get update
        $SUDO apt-get install -y docker.io docker-compose-v2 || $SUDO apt-get install -y docker.io docker-compose-plugin
    elif command -v dnf >/dev/null 2>&1; then
        $SUDO dnf install -y moby-engine docker-compose-plugin || $SUDO dnf install -y docker docker-compose
    elif command -v yum >/dev/null 2>&1; then
        $SUDO yum install -y docker docker-compose-plugin
    elif command -v zypper >/dev/null 2>&1; then
        $SUDO zypper --non-interactive install docker docker-compose
    elif command -v pacman >/dev/null 2>&1; then
        $SUDO pacman -Sy --needed --noconfirm docker docker-compose
    elif command -v apk >/dev/null 2>&1; then
        $SUDO apk add docker docker-cli-compose
    else
        fail "Kein unterstützter Paketmanager gefunden. / No supported package manager found."
    fi
    if command -v systemctl >/dev/null 2>&1; then $SUDO systemctl enable --now docker; fi
    docker compose version >/dev/null 2>&1 || fail "Docker Compose wurde nicht verfügbar. / Docker Compose did not become available."
}

install_docker
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
    command -v sudo >/dev/null 2>&1 || fail "Kein Zugriff auf den Docker-Daemon. / Cannot access the Docker daemon."
    sudo docker info >/dev/null 2>&1 || fail "Docker-Daemon ist nicht erreichbar. / Docker daemon is unavailable."
    DOCKER="sudo docker"
fi

case "$GPU" in
    nvidia)
        command -v nvidia-smi >/dev/null 2>&1 || fail "NVIDIA-Treiber fehlt. / NVIDIA driver is missing."
        $DOCKER info --format '{{json .Runtimes}}' | grep -q nvidia || fail "NVIDIA Container Toolkit fehlt: https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html / NVIDIA Container Toolkit is missing."
        overlay="nvidia.yml"
        ;;
    amd)
        [ -e /dev/kfd ] && [ -e /dev/dri ] || fail "AMDGPU/ROCm-Geräte fehlen (/dev/kfd, /dev/dri). / AMDGPU/ROCm devices are missing (/dev/kfd, /dev/dri)."
        overlay="amd.yml"
        ;;
    none) overlay="" ;;
esac

mkdir -p "$INSTALL_DIR"
curl -fL "$RELEASE_BASE/pc-agent.compose.yml" -o "$INSTALL_DIR/compose.yml"
if [ -n "$overlay" ]; then curl -fL "$RELEASE_BASE/pc-agent.$GPU.compose.yml" -o "$INSTALL_DIR/$overlay"; fi
cd "$INSTALL_DIR"
if [ -n "$overlay" ]; then
    $DOCKER compose -f compose.yml -f "$overlay" config --quiet
    $DOCKER compose -f compose.yml -f "$overlay" up -d
else
    $DOCKER compose -f compose.yml config --quiet
    $DOCKER compose -f compose.yml up -d
fi
say "PC-Agent läuft auf http://127.0.0.1:8084/ / PC Agent is running at http://127.0.0.1:8084/"
