#!/usr/bin/env bash
# Records what the game really publishes (one file per prompt kind and a few info records) into
# app/src/test/resources/fixtures, for the app's JVM tests (ParseTest).  Run it when a format changes:
#   ./gen-fixtures.sh && ./gradlew testDebugUnitTest
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
work=$(mktemp -d)
out="$here/app/src/test/resources/fixtures"
build="$work/build"
T=16777216   # NB_PANEL_TAB: a tab switch answered to any prompt in the panel

cmake -S "$here/app/src/main/cpp" -B "$build" -DCMAKE_BUILD_TYPE=Debug >/dev/null
cmake --build "$build" -j"$(nproc)" >/dev/null
run() {
    rm -rf "$work/data"
    timeout 120 "$build/narsil_host" "$root/lib" "$work/data" "$@" >"$work/log" 2>&1 || { echo "scenario failed: $*" >&2; tail -5 "$work/log" >&2; exit 1; }
}
P() { echo "@promptfile=$out/$1.prompt"; }
I() { echo "@infofile=$out/$1.info"; }
quit=(@esc @esc ^x @enter @esc)

rm -rf "$out"; mkdir -p "$out"

# Welcome, then every birth stage; the character is left at the map.
run $(P welcome) @answer=0 $(P birth_race) @answer=0 $(P birth_house) @answer=0 $(P birth_sex) @answer=0 \
    $(P birth_stats) @answer=2 $(P birth_skills) @answer=2 $(P birth_name) @text= $(P birth_ahw) @answer=0 \
    $(P birth_history) @answer=0 @esc "${quit[@]}"

birth=(@answer=0 @answer=0 @answer=0 @answer=0 @answer=2 @answer=2 @text= @answer=0 @answer=0 @esc)

# The panel's tabs, the pack expanded, and what Pack's actions lead to.
run "${birth[@]}" @act=15 $(P pack) @answer=1000 $(P pack_expanded) @answer=$((T+1)) $(P character) \
    @answer=$((T+2)) $(P skills) @answer=$((T+3)) $(P abilities) @answer=$((T+4)) $(P knowledge_main) \
    @answer=1 $(P knowledge_objects) @answer=-1 @answer=$((T+5)) $(P smith_main) @answer=$((T+6)) $(P options_main) \
    @answer=-1 "${quit[@]}"
run "${birth[@]}" @act=15 @answer=1001 @answer=100103 $(P quantity) @answer=1 @enter @enter \
    @answer=1000 @answer=100003 @enter $(P text) @text=lunch @answer=1000 @answer=100005 $(P choice_ignore) \
    @answer=-1 @answer=-1 "${quit[@]}"

# An item prompt, a yes/no, an aim and a direction, and the knowledge views.
run "${birth[@]}" w $(P items) @pick=-1 "${quit[@]}"
run "${birth[@]}" Q $(P check) @answer=0 "${quit[@]}"
run "${birth[@]}" t @pick=0 $(P aim) @answer=-1 "${quit[@]}"
run "${birth[@]}" T $(P direction) @answer=-1 "${quit[@]}"
run "${birth[@]}" @act=27 @answer=3 $(P knowledge_artefacts) @answer=-1 @answer=8 $(P knowledge_scores) @answer=-1 @answer=-1 "${quit[@]}"

# Info records: inspecting a grid, and the look list.
run "${birth[@]}" @inspect@0,0 $(I inspect) @look $(I look) "${quit[@]}"

# Death.
run "${birth[@]}" @die @enter $(P death)

echo "wrote $(ls "$out" | wc -l) fixtures to $out"
