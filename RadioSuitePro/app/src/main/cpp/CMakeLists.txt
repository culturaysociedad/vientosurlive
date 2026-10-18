# CMakeLists.txt — RadioSuitePro motor nativo (NDK)
cmake_minimum_required(VERSION 3.22.1)
project(radiosuite C CXX)

include(FetchContent)
include(ExternalProject)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build codec dependencies as static libraries" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "Disable dependency tests for Android builds" FORCE)
set(INSTALL_DOCS OFF CACHE BOOL "Disable codec documentation installs" FORCE)
set(INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "Disable pkg-config generation" FORCE)
set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "Disable CMake package installs" FORCE)

# --- Oboe (AAudio / OpenSL ES) ---
find_package(oboe REQUIRED CONFIG)

# --- Codec sources are fetched at fixed versions and built for each Android ABI. ---
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

FetchContent_Declare(
    ogg
    URL https://codeload.github.com/xiph/ogg/tar.gz/refs/tags/v1.3.5
    URL_HASH SHA256=f6f1b04cfa4e98b70ffe775d5e302d9c6b98541f05159af6de2d6617817ed7d6
)
FetchContent_MakeAvailable(ogg)

FetchContent_Declare(
    vorbis
    URL https://codeload.github.com/xiph/vorbis/tar.gz/refs/tags/v1.3.7
    URL_HASH SHA256=270c76933d0934e42c5ee0a54a36280e2d87af1de3cc3e584806357e237afd13
)
FetchContent_MakeAvailable(vorbis)

set(OPUS_BUILD_TESTING OFF CACHE BOOL "Disable Opus tests" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Disable Opus programs" FORCE)
FetchContent_Declare(
    opus
    URL https://codeload.github.com/xiph/opus/tar.gz/refs/tags/v1.5.2
    URL_HASH SHA256=9480e329e989f70d69886ded470c7f8cfe6c0667cc4196d4837ac9e668fb7404
)
FetchContent_MakeAvailable(opus)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LAME_HOST aarch64-linux-android)
    set(LAME_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(LAME_HOST arm-linux-androideabi)
    set(LAME_TARGET armv7a-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(LAME_HOST x86_64-linux-android)
    set(LAME_TARGET x86_64-linux-android)
else()
    message(FATAL_ERROR "Unsupported Android ABI for LAME: ${ANDROID_ABI}")
endif()

set(LAME_API_LEVEL 26)
set(LAME_NDK_BIN "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
set(LAME_SYSROOT "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot")
set(LAME_CFLAGS "--target=${LAME_TARGET}${LAME_API_LEVEL} --sysroot=${LAME_SYSROOT} -fPIC")
set(LAME_LIBRARY "${CMAKE_CURRENT_BINARY_DIR}/lame-build/libmp3lame/.libs/libmp3lame.a")

FetchContent_Declare(
    lame
    URL https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
    URL_HASH SHA256=ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
)
FetchContent_GetProperties(lame)
if(NOT lame_POPULATED)
    FetchContent_Populate(lame)
endif()

find_program(MAKE_EXECUTABLE make REQUIRED)
ExternalProject_Add(lame_external
    SOURCE_DIR "${lame_SOURCE_DIR}"
    BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/lame-build"
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    CONFIGURE_COMMAND
        ${CMAKE_COMMAND} -E env
        "CC=${LAME_NDK_BIN}/clang"
        "AR=${LAME_NDK_BIN}/llvm-ar"
        "RANLIB=${LAME_NDK_BIN}/llvm-ranlib"
        "CFLAGS=${LAME_CFLAGS}"
        "LDFLAGS=--target=${LAME_TARGET}${LAME_API_LEVEL} --sysroot=${LAME_SYSROOT}"
        <SOURCE_DIR>/configure
        --host=${LAME_HOST}
        --disable-shared
        --enable-static
        --disable-frontend
        --disable-decoder
    BUILD_COMMAND ${MAKE_EXECUTABLE}
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${LAME_LIBRARY}"
)

add_library(mp3lame STATIC IMPORTED GLOBAL)
set_target_properties(mp3lame PROPERTIES
    IMPORTED_LOCATION "${LAME_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${lame_SOURCE_DIR}/include"
)
add_dependencies(mp3lame lame_external)

# --- Biblioteca principal ---
add_library(radiosuite-native SHARED
    jni/jni_bridge.cpp
    audio/AudioPipeline.cpp
    audio/processors/Agc.cpp
    audio/processors/ParametricEq.cpp
    audio/processors/MultibandComp.cpp
    audio/processors/StereoEnhancer.cpp
    audio/processors/BrickwallLimiter.cpp
    audio/dsp/Biquad.cpp
    player/AudioMixer.cpp
    audio/engine/OboeRenderer.cpp
    stream/IcecastEncoder.cpp
    stream/RingBuffer.cpp
    jni/NativeEngine.cpp
)

target_include_directories(radiosuite-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)
find_library(android-lib android)
find_library(m-lib m)

target_link_libraries(radiosuite-native
    oboe::oboe
    ogg vorbis vorbisenc opus mp3lame
    ${log-lib}
    ${android-lib}
    ${m-lib}
)
add_dependencies(radiosuite-native lame_external)

# Optimizaciones de tiempo real: sin excepciones, sin RTTI, -O3.
target_compile_options(radiosuite-native PRIVATE
    -O3 -ffast-math -fno-exceptions -fno-rtti
    -Wall -Wextra -Wno-unused-parameter
)
