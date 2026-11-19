#!/usr/bin/env bash
# Plays NarSil-Q with a desktop build of NarSil.
# - The game data is lib with lib-silq copied over it, rebuilt each run in build-silq/lib.
# - Saves, scores and user files are kept apart in build-silq/user, so the two games never share a save directory.
# Arguments are passed to the game, e.g.:  tools/narsil-q.sh -mgcu
# NARSIL=<path> picks the executable (default src/narsil, as built by ./configure --with-no-install && make).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
exe=${NARSIL:-$root/src/narsil}
out=$root/build-silq
[ -x "$exe" ] || { echo "narsil-q: no executable at $exe (build NarSil first, or set NARSIL)" >&2; exit 1; }
"$root/tools/merge-lib.sh" "$root/lib" "$root/lib-silq" "$out/lib"
for d in user save scores panic archive; do mkdir -p "$out/user/$d"; done
exec "$exe" \
    -dgamedata="$out/lib/gamedata" -dscreens="$out/lib/screens" -dhelp="$out/lib/help" \
    -dpref="$out/lib/customize" -dtiles="$out/lib/tiles" \
    -duser="$out/user/user" -dsave="$out/user/save" -dscores="$out/user/scores" \
    -dpanic="$out/user/panic" -darchive="$out/user/archive" \
    "$@"
