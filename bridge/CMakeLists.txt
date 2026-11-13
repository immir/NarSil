# Builds the NarSil core plus the front-end-neutral bridge (narsil_bridge.h is its whole API).
#  - Standalone, on the host: narsil_host, a scripted harness for testing the bridge
#      cmake -S bridge -B build-host && cmake --build build-host
#  - From android/ (libnarsil.so adds the JNI glue) or ios/ (an xcframework): add_subdirectory(bridge).
cmake_minimum_required(VERSION 3.18)
project(narsil_bridge_project C)

get_filename_component(NARSIL_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)

# Every core/UI source except the platform front ends.
file(GLOB NARSIL_CORE_SOURCES CONFIGURE_DEPENDS "${NARSIL_ROOT}/src/*.c")
list(FILTER NARSIL_CORE_SOURCES EXCLUDE REGEX "/(main[^/]*|snd-[^/]*)\\.c$")

add_library(narsil_core STATIC ${NARSIL_CORE_SOURCES})
target_include_directories(narsil_core PUBLIC "${NARSIL_ROOT}/src")
set_target_properties(narsil_core PROPERTIES C_STANDARD 99 POSITION_INDEPENDENT_CODE ON)
target_compile_options(narsil_core PRIVATE -w)

find_package(Threads REQUIRED)
add_library(narsil_bridge STATIC narsil_bridge.c narsil_native.c narsil_prompt.c narsil_smith.c narsil_birth.c narsil_options.c narsil_knowledge.c narsil_tiles.c)
set_target_properties(narsil_bridge PROPERTIES C_STANDARD 11 POSITION_INDEPENDENT_CODE ON)
# NB_SYS_NAME is the game's $SYS (pref-file conditions); NB_TEST_HOOKS builds the host-only test calls.
set(NB_SYS_NAME "android" CACHE STRING "Platform name the game reports as $SYS (android, ios, host)")
target_compile_definitions(narsil_bridge PRIVATE _POSIX_C_SOURCE=200809L NB_SYS_NAME="${NB_SYS_NAME}")
if(PROJECT_IS_TOP_LEVEL)
    target_compile_definitions(narsil_bridge PUBLIC NB_TEST_HOOKS)
endif()
target_compile_options(narsil_bridge PRIVATE -Wall -Wextra)
target_link_libraries(narsil_bridge PUBLIC narsil_core Threads::Threads)

target_include_directories(narsil_bridge PUBLIC "${CMAKE_CURRENT_LIST_DIR}")

if(PROJECT_IS_TOP_LEVEL)
    add_executable(narsil_host host_harness.c)
    set_target_properties(narsil_host PROPERTIES C_STANDARD 11)
    target_compile_definitions(narsil_host PRIVATE _POSIX_C_SOURCE=200809L)
    target_compile_options(narsil_host PRIVATE -Wall -Wextra)
    target_link_libraries(narsil_host PRIVATE narsil_bridge)
endif()
