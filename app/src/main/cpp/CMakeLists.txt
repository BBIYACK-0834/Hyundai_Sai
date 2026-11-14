cmake_minimum_required(VERSION 3.22.1)
project(sai_speech LANGUAGES C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# The Pleos x86_64 emulator may expose a subset of the host CPU features.
set(GGML_AVX OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
set(GGML_FMA OFF CACHE BOOL "" FORCE)
set(GGML_F16C OFF CACHE BOOL "" FORCE)
set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Keep inference usable in Android Studio's default Debug build.
string(APPEND CMAKE_C_FLAGS_DEBUG " -O2")
string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O2")

FetchContent_Declare(whisper
    URL https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/a8d002cfd879315632a579e73f0148d06959de36
    URL_HASH SHA256=7b17da903114ed45d82f48c030e3be6a5a8a279f884b2f733706dbb4e832fb6b
    TLS_VERIFY ON)
FetchContent_MakeAvailable(whisper)

add_library(sai_speech SHARED transcribe.cpp)
target_compile_features(sai_speech PRIVATE cxx_std_17)
target_link_libraries(sai_speech PRIVATE whisper)
