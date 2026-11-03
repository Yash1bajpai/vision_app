cmake_minimum_required(VERSION 3.22.1)
project(vision_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable native-arch detection for Android cross-compilation
set(GGML_NATIVE OFF CACHE BOOL "disable native-arch detection" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "disable native-arch detection" FORCE)

# Build only needed targets for Android library
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "disable tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "disable tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "disable examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "disable server" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "disable common" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build static internal libs" FORCE)

add_subdirectory(llama.cpp)

add_library(vision_llama SHARED
    vision_llama_jni.cpp
)

target_include_directories(vision_llama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(vision_llama PRIVATE
    llama
    android
    log
)
