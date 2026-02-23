cmake_minimum_required(VERSION 3.22.1)
project(gguf_jni)

# Keep native code optimized even for debug APK builds to avoid misleadingly slow inference.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/llama.cpp)

add_library(
    gguf_jni
    SHARED
    gguf_jni.cpp
)

target_include_directories(gguf_jni PRIVATE third_party/llama.cpp/include)

find_library(log-lib log)

target_link_libraries(
    gguf_jni
    llama
    ${log-lib}
)
