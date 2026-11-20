cmake_minimum_required(VERSION 3.22)
project(ai254)
# Unoptimised whisper.cpp is far too slow to caption in real time, so compile
# the native code optimised even in debug APKs.
set(CMAKE_BUILD_TYPE Release)
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper
  URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.6.tar.gz)
FetchContent_MakeAvailable(whisper)
add_library(ai254 SHARED bridge.cpp)
target_link_libraries(ai254 PRIVATE whisper log)
