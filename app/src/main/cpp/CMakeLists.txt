# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(schwungcam CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LINK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/link)

add_library(schwungcam_link SHARED stems_core.cpp link_jni.cpp)
# Android is Linux to the Link SDK: ClockMonotonicRaw, getifaddrs (API 24+).
target_compile_definitions(schwungcam_link PRIVATE LINK_PLATFORM_LINUX=1)
target_compile_options(schwungcam_link PRIVATE -fexceptions -frtti -Wno-deprecated-declarations)
target_include_directories(schwungcam_link PRIVATE
    ${LINK_DIR}/include
    ${LINK_DIR}/modules/asio-standalone/asio/include)
target_link_libraries(schwungcam_link log)
# 16 KB page devices refuse (or warn on) a library whose LOAD segments are 4 KB aligned
target_link_options(schwungcam_link PRIVATE "-Wl,-z,max-page-size=16384")

# the mastering limiter's true-peak detector (dsp/NativePeaks.kt); no Link in it
add_library(schwungcam_dsp SHARED truepeak_core.cpp dsp_jni.cpp)
target_compile_options(schwungcam_dsp PRIVATE -O3)
target_link_options(schwungcam_dsp PRIVATE "-Wl,-z,max-page-size=16384")
