cmake_minimum_required(VERSION 3.24)
project(assistant_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# llama.cpp is linked statically into our .so, so it must be position-independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp, pinned. To upgrade: change the tag here and check llama_jni.cpp still builds.
set(LLAMA_TAG "b11550" CACHE STRING "llama.cpp release tag")

include(FetchContent)
FetchContent_Declare(
    llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)

# Only the core library: no tools, servers, tests or networking.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)

# One CPU build for the phone instead of runtime-loaded variants.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # ggml's own thread pool; avoids shipping libomp
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE) # fast ARM kernels for Q4_0 models
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Helio G99 (Cortex-A76/A55) and most phones since 2019: ARMv8.2 with dot-product + fp16.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(llama)

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni PRIVATE llama)
if(ANDROID)
    target_link_libraries(llama_jni PRIVATE android log)
    # Keep the .so small and load fast: only the JNI functions are exported.
    target_link_options(llama_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
endif()
