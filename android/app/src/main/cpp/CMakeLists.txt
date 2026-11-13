# libnarsil.so: the shared bridge (../../../../../bridge) plus the JNI glue.
cmake_minimum_required(VERSION 3.18)
project(narsil_android C)

add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../../../../bridge" bridge)

add_library(narsil SHARED narsil_jni.c)
set_target_properties(narsil PROPERTIES C_STANDARD 11)
target_compile_options(narsil PRIVATE -Wall -Wextra)
target_link_libraries(narsil PRIVATE narsil_bridge log)
