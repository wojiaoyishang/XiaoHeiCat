cmake_minimum_required(VERSION 3.22.1)

# Android Gradle Plugin already owns the CMake configure step.
# On some Windows/NDK setups Ninja repeatedly decides build.ninja is dirty
# and re-runs CMake until it fails with:
#   manifest 'build.ninja' still dirty after 100 tries
# Suppress Ninja's self-regeneration rule; Gradle will reconfigure CMake when
# app/.cxx is cleaned or CMake arguments change.
set(CMAKE_SUPPRESS_REGENERATION ON)

project(xhh_dexscan LANGUAGES CXX)

add_library(xhh_dexscan SHARED xhh_dex_memory_scanner.cpp)

target_compile_features(xhh_dexscan PRIVATE cxx_std_17)
target_compile_options(xhh_dexscan PRIVATE -Wall -Wextra -Wno-unused-parameter)

find_library(log-lib log)
target_link_libraries(xhh_dexscan ${log-lib})
