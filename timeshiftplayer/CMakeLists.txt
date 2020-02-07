# Sets the minimum version of CMake required to build your native library.
# This ensures that a certain set of CMake features is available to
# your build.

cmake_minimum_required(VERSION 3.4.1)

# Specifies a library name, specifies whether the library is STATIC or
# SHARED, and provides relative paths to the source code. You can
# define multiple libraries by adding multiple add_library() commands,
# and CMake builds them for you. When you build your app, Gradle
# automatically packages shared libraries with your APK.

#######################################
# directories
#######################################
set(FFMPEG_DIR ${CMAKE_CURRENT_LIST_DIR}/libs/${ANDROID_ABI})
include_directories(tsresampler ${FFMPEG_DIR}/include)

#######################################
# add libraries
#######################################
add_library( avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/lib/libavformat.so)
add_library( avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/lib/libavutil.so)
add_library( avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION  ${FFMPEG_DIR}/lib/libavcodec.so)
add_library( swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/lib/libswresample.so)

add_library(tsresampler SHARED src/main/cpp/native-lib.cpp)

target_link_libraries( tsresampler avformat avutil avcodec swresample log)
