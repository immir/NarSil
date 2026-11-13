#!/usr/bin/env bash
# Builds the core and bridge for iOS (device and simulator) and packs them into build/NarSilCore.xcframework,
# which the Xcode project links.  Needs macOS with Xcode and CMake.  Also regenerates NarSil/NBConstants.swift.
#   ./build-core.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out="$here/build"
rm -rf "$out/xcframework" "$out/include"; mkdir -p "$out/include"

python3 "$here/../bridge/gen-swift-constants.py" "$here/NarSil/NBConstants.swift"

# One static library per platform: the core and the bridge merged, so Swift links a single archive.
build_platform() {  # name, toolchain PLATFORM
    local dir="$out/$1"
    cmake -S "$here" -B "$dir" -G Xcode -DCMAKE_TOOLCHAIN_FILE="$here/cmake/ios.toolchain.cmake" \
        -DPLATFORM="$2" -DDEPLOYMENT_TARGET=16.0 -DNB_SYS_NAME=ios -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
    cmake --build "$dir" --config Release -j"$(sysctl -n hw.physicalcpu)" >/dev/null
    libtool -static -o "$dir/libNarSilCore.a" "$dir"/bridge/Release-*/libnarsil_core.a "$dir"/bridge/Release-*/libnarsil_bridge.a
}
build_platform device OS64
build_platform simulator SIMULATORARM64

cp "$here/../bridge/narsil_bridge.h" "$out/include/"
cat >"$out/include/module.modulemap" <<'M'
module NarSilCore {
    header "narsil_bridge.h"
    export *
}
M
xcodebuild -create-xcframework \
    -library "$out/device/libNarSilCore.a" -headers "$out/include" \
    -library "$out/simulator/libNarSilCore.a" -headers "$out/include" \
    -output "$out/NarSilCore.xcframework"
echo "wrote $out/NarSilCore.xcframework"
