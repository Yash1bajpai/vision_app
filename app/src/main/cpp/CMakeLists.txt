cmake_minimum_required(VERSION 3.22.1)
project(vision_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable native-arch detection for Android cross-compilation
set(GGML_NATIVE OFF CACHE BOOL "disable native-arch detection" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "disable native-arch detection" FORCE)

# Scoped CI diagnostic, opt-in only. With GGML_NATIVE OFF and
# CMAKE_CROSSCOMPILING true, pinned ggml sets INS_ENB OFF, which defaults every
# x86 SIMD option (SSE4.2/AVX/AVX2/FMA/F16C/BMI2) to OFF - the shipped x86_64
# ggml-cpu is scalar. That keeps baseline compatibility with any x86_64 device
# but makes prefill unusably slow on the CI emulator. This option lets the
# instrumentation workflow enable SIMD for the x86_64 ABI ONLY, after the
# workflow verifies the emulator guest exposes the features in /proc/cpuinfo.
# Default OFF: production/local builds are byte-for-byte the previous config.
# ARM64 is untouched by this block.
option(VISION_CI_X86_SIMD "CI-only: enable x86_64 SIMD for instrumentation diagnostics" OFF)
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    if (VISION_CI_X86_SIMD)
        set(GGML_SSE42 ON CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_AVX ON CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_AVX2 ON CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_FMA ON CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_F16C ON CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_BMI2 ON CACHE BOOL "scoped CI diagnostic" FORCE)
        message(STATUS "VISION_CI_X86_SIMD=ON for ${CMAKE_ANDROID_ARCH_ABI}: SSE4.2/AVX/AVX2/FMA/F16C/BMI2")
    else()
        # Deterministic OFF: a reused CMake build directory must not retain a
        # previous ON configuration (option() leaves existing cache entries
        # untouched), so the baseline path explicitly forces every flag OFF.
        set(GGML_SSE42 OFF CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_AVX OFF CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_AVX2 OFF CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_FMA OFF CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_F16C OFF CACHE BOOL "scoped CI diagnostic" FORCE)
        set(GGML_BMI2 OFF CACHE BOOL "scoped CI diagnostic" FORCE)
        message(STATUS "VISION_CI_X86_SIMD=OFF for ${CMAKE_ANDROID_ARCH_ABI}: baseline compatibility config")
    endif()
else()
    message(STATUS "VISION_CI_X86_SIMD not applicable for ${CMAKE_ANDROID_ARCH_ABI}: unchanged config")
endif()

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
