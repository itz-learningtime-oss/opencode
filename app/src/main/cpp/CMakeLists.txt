cmake_minimum_required(VERSION 3.22.1)
project(opencode_terminal C CXX)

add_library(opencode_terminal SHARED
    NativeTerminalJNI.c
    stl_anchor.cpp
)

target_compile_definitions(opencode_terminal PRIVATE _GNU_SOURCE)

target_compile_options(opencode_terminal PRIVATE
    -Wall
    -Wextra
    -O2
    -fvisibility=hidden
)

find_library(log-lib log)

# Linking c++_shared guarantees libc++_shared.so is packaged into the APK.
# nodejs-mobile's libnode.so declares it as a NEEDED dependency.
target_link_libraries(opencode_terminal
    ${log-lib}
    c++_shared
    ${CMAKE_DL_LIBS}
)
