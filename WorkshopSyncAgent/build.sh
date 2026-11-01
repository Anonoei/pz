#!/usr/bin/env bash
# Builds WorkshopSyncAgent.jar. Needs a JDK (21+) and the game's projectzomboid.jar for the
# zombie.* classes (compile-time only; not bundled).
#   ./build.sh /path/to/projectzomboid.jar
set -euo pipefail
cd "$(dirname "$0")"
PZ_JAR="${1:?usage: build.sh /path/to/projectzomboid.jar}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac --release 21 -Xlint:all -cp "$PZ_JAR" -d "$OUT" src/anopz/workshopsync/WorkshopSyncAgent.java
jar --create --file WorkshopSyncAgent.jar --manifest manifest.mf -C "$OUT" .
echo "built $(pwd)/WorkshopSyncAgent.jar"
