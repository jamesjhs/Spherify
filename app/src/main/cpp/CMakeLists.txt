cmake_minimum_required(VERSION 3.22.1)

project(spherify_stitcher)

if(NOT OpenCV_DIR)
    message(FATAL_ERROR "Set spherify.opencvNativeDir in local.properties to the directory containing OpenCVConfig.cmake")
endif()

find_package(OpenCV REQUIRED COMPONENTS stitching imgcodecs)

add_library(spherify_stitcher SHARED spherify_stitcher.cpp)

target_include_directories(spherify_stitcher PRIVATE ${OpenCV_INCLUDE_DIRS})

target_link_libraries(
        spherify_stitcher
        ${OpenCV_LIBS}
        android
        log)
