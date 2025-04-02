cmake_minimum_required(VERSION 3.10.2)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ggdb")

add_library(andsupport
    SHARED
    src/main/cpp/android.cpp
    src/main/cpp/detect.cpp
    src/main/cpp/calibration.cpp)

target_link_libraries(andsupport PUBLIC log)
