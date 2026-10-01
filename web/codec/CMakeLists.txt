cmake_minimum_required(VERSION 3.22)
project(zenptt_web_opus LANGUAGES C)
include(FetchContent)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(opus
    URL https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz
    URL_HASH SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1)
FetchContent_MakeAvailable(opus)
if(EMSCRIPTEN)
    add_executable(web_opus bridge.c)
    target_link_libraries(web_opus PRIVATE Opus::opus)
    set_target_properties(web_opus PROPERTIES OUTPUT_NAME opus SUFFIX .wasm)
    target_link_options(web_opus PRIVATE --no-entry -O3
        -sSTANDALONE_WASM=1 -sFILESYSTEM=0 -sALLOW_MEMORY_GROWTH=0
        -sINITIAL_MEMORY=16777216 -sSTACK_SIZE=1048576
        "-sEXPORTED_FUNCTIONS=['_zen_init','_zen_encode','_zen_decode','_zen_encoder_reset','_zen_decoder_reset','_zen_input','_zen_output','_zen_packet']")
else()
    add_executable(native-reference reference.c)
    target_link_libraries(native-reference PRIVATE Opus::opus)
endif()
