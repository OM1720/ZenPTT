cmake_minimum_required(VERSION 3.22.1)
project(zenptt_opus LANGUAGES C CXX)

include(FetchContent)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    opus
    URL https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz
    URL_HASH SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
)
FetchContent_MakeAvailable(opus)

add_library(zenptt_opus SHARED opus_jni.cpp)
target_compile_features(zenptt_opus PRIVATE cxx_std_17)
target_compile_options(zenptt_opus PRIVATE -Wall -Wextra -Werror)
target_link_libraries(zenptt_opus PRIVATE Opus::opus)
