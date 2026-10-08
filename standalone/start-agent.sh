#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec java -jar solarminer-pc-agent-standalone.jar "$@" --solarminer.agent.standalone=true
