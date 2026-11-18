#!/usr/bin/env bash
# Builds a game's lib dir: lib, with an overlay's files copied over it.
#   tools/merge-lib.sh <lib> <overlay> <out>
set -euo pipefail
lib=$1 overlay=$2 out=$3
[ -d "$lib/gamedata" ] && [ -d "$overlay/gamedata" ] || { echo "merge-lib: need $lib/gamedata and $overlay/gamedata" >&2; exit 1; }
rm -rf "$out"
mkdir -p "$out"
cp -R "$lib"/. "$out"/
(cd "$overlay" && find . -type f ! -name README.md -print0) | while IFS= read -r -d '' f; do
    mkdir -p "$out/$(dirname "$f")"
    cp "$overlay/$f" "$out/$f"
done
