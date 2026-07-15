cmake_minimum_required(VERSION 3.22.1)

project(canopy_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")

set(BUILD_SHARED_LIBS ON CACHE BOOL "Build llama.cpp shared libraries" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama" EXCLUDE_FROM_ALL)

add_library(canopy_llama SHARED canopy_llama.cpp)

target_include_directories(canopy_llama PRIVATE
    "${LLAMA_SOURCE_DIR}/include"
    "${LLAMA_SOURCE_DIR}/ggml/include"
    "${LLAMA_SOURCE_DIR}/tools/mtmd"
)

target_link_libraries(canopy_llama PRIVATE
    llama
    mtmd
    android
    log
)
