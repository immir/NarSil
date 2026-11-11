# Builds the NarSil core plus the bridge.
#  - Under the NDK (ANDROID set): libnarsil.so with the JNI glue.
#  - On the host: narsil_host, a scripted harness for testing the bridge
#      cmake -S android/app/src/main/cpp -B build-host && cmake --build build-host
cmake_minimum_required(VERSION 3.18)
project(narsil_android C)

get_filename_component(NARSIL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)

# Every core/UI source except the platform front ends.
file(GLOB NARSIL_CORE_SOURCES CONFIGURE_DEPENDS "${NARSIL_ROOT}/src/*.c")
list(FILTER NARSIL_CORE_SOURCES EXCLUDE REGEX "/(main[^/]*|snd-[^/]*)\\.c$")

add_library(narsil_core STATIC ${NARSIL_CORE_SOURCES})
target_include_directories(narsil_core PUBLIC "${NARSIL_ROOT}/src")
set_target_properties(narsil_core PROPERTIES C_STANDARD 99 POSITION_INDEPENDENT_CODE ON)
target_compile_options(narsil_core PRIVATE -w)

find_package(Threads REQUIRED)
add_library(narsil_bridge STATIC narsil_bridge.c narsil_native.c narsil_prompt.c)
set_target_properties(narsil_bridge PROPERTIES C_STANDARD 11 POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(narsil_bridge PRIVATE _POSIX_C_SOURCE=200809L)
target_compile_options(narsil_bridge PRIVATE -Wall -Wextra)
target_link_libraries(narsil_bridge PUBLIC narsil_core Threads::Threads)

if(ANDROID)
    add_library(narsil SHARED narsil_jni.c)
    set_target_properties(narsil PROPERTIES C_STANDARD 11)
    target_compile_options(narsil PRIVATE -Wall -Wextra)
    target_link_libraries(narsil PRIVATE narsil_bridge log)
else()
    add_executable(narsil_host host_harness.c)
    set_target_properties(narsil_host PROPERTIES C_STANDARD 11)
    target_compile_definitions(narsil_host PRIVATE _POSIX_C_SOURCE=200809L)
    target_compile_options(narsil_host PRIVATE -Wall -Wextra)
    target_link_libraries(narsil_host PRIVATE narsil_bridge)
endif()
