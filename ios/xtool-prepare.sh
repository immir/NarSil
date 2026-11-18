#!/usr/bin/env bash
# Stages the game data xtool copies into the app (ios/lib, the same trimmed set project.yml copies, and ios/lib-silq
# for NarSil-Q), then regenerates NBConstants.swift.
#   ./xtool-prepare.sh && xtool dev
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../lib"; dest="$here/lib"
python3 "$here/../bridge/gen-swift-constants.py" "$here/NarSil/NBConstants.swift"
rm -rf "$dest"; mkdir -p "$dest/tiles"
for d in gamedata customize help screens; do cp -R "$src/$d" "$dest/$d"; done
cp "$src/tiles/list.txt" "$dest/tiles/"; cp -R "$src/tiles/microchasm" "$dest/tiles/"
find "$dest" -name Makefile -delete
# NarSil-Q: the same data with lib-silq copied over it
"$here/../tools/merge-lib.sh" "$dest" "$here/../lib-silq" "$here/lib-silq"
