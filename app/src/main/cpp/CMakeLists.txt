cmake_minimum_required(VERSION 3.22.1)
project(utamaru_rubberband LANGUAGES CXX)

set(RUBBERBAND_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/rubberband-4.0.0")
if(NOT EXISTS "${RUBBERBAND_SOURCE}/single/RubberBandSingle.cpp")
    message(FATAL_ERROR "Rubber Band 4.0.0 sources missing: run SETUP_RUBBERBAND.bat")
endif()

# Upstream single-file unit includes its internal sources by relative path.
add_library(utamaru_rubberband SHARED
    utamaru_rubberband.cpp
    "${RUBBERBAND_SOURCE}/single/RubberBandSingle.cpp")
target_compile_features(utamaru_rubberband PRIVATE cxx_std_17)
target_compile_options(utamaru_rubberband PRIVATE -O2 -fexceptions -frtti)
target_include_directories(utamaru_rubberband PRIVATE "${RUBBERBAND_SOURCE}")
find_library(android-log log)
target_link_libraries(utamaru_rubberband PRIVATE ${android-log})
