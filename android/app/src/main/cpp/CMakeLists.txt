cmake_minimum_required(VERSION 3.22.1)
project(WangshuInput)
set(CMAKE_CXX_STANDARD 17)
get_filename_component(IME_CORE "${CMAKE_CURRENT_LIST_DIR}/../../../../../core" ABSOLUTE)
add_library(wangshuime SHARED bridge.cpp ${IME_CORE}/engine.cpp)
target_include_directories(wangshuime PRIVATE ${IME_CORE})
target_compile_options(wangshuime PRIVATE -Wall -Wextra -O2)

# NDK r27: make both ELF LOAD and RELRO layout explicit for this target.
# 16 KiB alignment on 32-bit ABIs is consistency, not a platform requirement.
if(ANDROID)
    target_link_options(wangshuime PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endif()
