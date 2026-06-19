cmake_minimum_required(VERSION 3.20)
project(jlloc-native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(jlloc-native SHARED
    src/jlloc_agent.cpp
)

target_include_directories(jlloc-native PRIVATE
    include
    ${JNI_INCLUDE_DIRS}
)
