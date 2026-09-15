cmake_minimum_required(VERSION 3.22.1)
project(espeak_bridge)

# 16KB page-size alignment for Android 15+ (Pixel 9 etc.)
add_link_options("LINKER:-z,max-page-size=16384" "LINKER:-z,common-page-size=16384")

# Real espeak-ng: prebuilt per-ABI static-equivalent (.so) vendored under
# cpp/espeak-ng/<abi>/libespeak-ng.so, built from upstream source with the
# NDK (AUDIO_OUTPUT_SYNCHRONOUS path only — no audio deps needed at runtime).
add_library(espeak-ng SHARED IMPORTED)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set_target_properties(espeak-ng PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng/arm64-v8a/libespeak-ng.so)
else()
    set_target_properties(espeak-ng PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng/armeabi-v7a/libespeak-ng.so)
endif()

add_library(espeak_bridge SHARED espeak_bridge.cpp)

target_include_directories(espeak_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(espeak_bridge espeak-ng ${log-lib})
