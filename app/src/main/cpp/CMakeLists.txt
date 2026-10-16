cmake_minimum_required(VERSION 3.22.1)
project(camstudio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(camstudio SHARED native-lib.cpp image_processing.cpp)
target_compile_options(camstudio PRIVATE -O3 -fvisibility=hidden)
# Android 15+ devices may use 16 KB pages
target_link_options(camstudio PRIVATE -Wl,-z,max-page-size=16384)
find_library(log-lib log)
target_link_libraries(camstudio jnigraphics ${log-lib})
