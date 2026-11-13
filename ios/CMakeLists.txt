# The game core plus the bridge as static libraries for iOS (see build-core.sh, which makes the xcframework).
cmake_minimum_required(VERSION 3.18)
project(narsil_ios C)
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bridge" bridge)
