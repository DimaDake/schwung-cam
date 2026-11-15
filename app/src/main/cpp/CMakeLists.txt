# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(schwungcam_link CXX)

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
