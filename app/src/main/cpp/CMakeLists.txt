cmake_minimum_required(VERSION 3.22.1)
project(utamaru_pitch24_variants LANGUAGES CXX)

set(THIRD_PARTY "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party")
set(RUBBERBAND_SOURCE "${THIRD_PARTY}/rubberband-4.0.0")
set(SIGNALSMITH_SOURCE "${THIRD_PARTY}/signalsmith-stretch")
if(NOT EXISTS "${RUBBERBAND_SOURCE}/single/RubberBandSingle.cpp")
    message(FATAL_ERROR "Rubber Band 4.0.0 missing. Run SETUP_PITCH24.bat at project root.")
endif()
# Prepare Signalsmith automatically during Android Studio Gradle Sync, without any BAT.
# Existing third_party trees are never altered; downloads occur only if required files are absent.
include(FetchContent)
set(FETCHCONTENT_UPDATES_DISCONNECTED TRUE)
if(NOT EXISTS "${SIGNALSMITH_SOURCE}/signalsmith-stretch.h")
    message(STATUS "UtaMaru: fetching Signalsmith Stretch for the first build")
    FetchContent_Declare(utamaru_signalsmith_stretch
        GIT_REPOSITORY https://github.com/Signalsmith-Audio/signalsmith-stretch.git
        GIT_TAG main
        GIT_SHALLOW TRUE
        SOURCE_DIR "${SIGNALSMITH_SOURCE}")
    FetchContent_GetProperties(utamaru_signalsmith_stretch)
    if(NOT utamaru_signalsmith_stretch_POPULATED)
        FetchContent_Populate(utamaru_signalsmith_stretch)
    endif()
endif()
if(NOT EXISTS "${THIRD_PARTY}/signalsmith-linear/stft.h")
    message(STATUS "UtaMaru: fetching Signalsmith Linear for the first build")
    FetchContent_Declare(utamaru_signalsmith_linear
        GIT_REPOSITORY https://github.com/Signalsmith-Audio/linear.git
        GIT_TAG main
        GIT_SHALLOW TRUE
        SOURCE_DIR "${THIRD_PARTY}/signalsmith-linear")
    FetchContent_GetProperties(utamaru_signalsmith_linear)
    if(NOT utamaru_signalsmith_linear_POPULATED)
        FetchContent_Populate(utamaru_signalsmith_linear)
    endif()
endif()
if(NOT EXISTS "${SIGNALSMITH_SOURCE}/signalsmith-stretch.h" OR
   NOT EXISTS "${THIRD_PARTY}/signalsmith-linear/stft.h" OR
   NOT EXISTS "${THIRD_PARTY}/signalsmith-linear/fft.h")
    message(FATAL_ERROR "Signalsmith dependency setup failed. Check Git/internet connectivity during Gradle Sync.")
endif()

add_library(utamaru_pitch24_variants SHARED
    utamaru_pitch24.cpp
    "${RUBBERBAND_SOURCE}/single/RubberBandSingle.cpp")
target_compile_features(utamaru_pitch24_variants PRIVATE cxx_std_17)
target_compile_options(utamaru_pitch24_variants PRIVATE -O2 -fexceptions -frtti)
target_include_directories(utamaru_pitch24_variants PRIVATE
    "${RUBBERBAND_SOURCE}" "${SIGNALSMITH_SOURCE}" "${THIRD_PARTY}")
find_library(android_log log)
target_link_libraries(utamaru_pitch24_variants PRIVATE ${android_log})
