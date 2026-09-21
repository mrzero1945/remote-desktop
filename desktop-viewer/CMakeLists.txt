cmake_minimum_required(VERSION 3.16)
project(DesktopViewer VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(PkgConfig REQUIRED)
find_package(Boost REQUIRED)
pkg_check_modules(SDL2 REQUIRED sdl2)
pkg_check_modules(AVCODEC REQUIRED libavcodec)
pkg_check_modules(AVUTIL REQUIRED libavutil)
pkg_check_modules(SWSCALE REQUIRED libswscale)

add_executable(desktop_viewer
    src/main.cpp
)

target_include_directories(desktop_viewer PRIVATE
    ${SDL2_INCLUDE_DIRS}
    ${AVCODEC_INCLUDE_DIRS}
    ${AVUTIL_INCLUDE_DIRS}
    ${SWSCALE_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/../server/src
)

target_link_libraries(desktop_viewer
    Boost::boost
    ${SDL2_LIBRARIES}
    ${AVCODEC_LIBRARIES}
    ${AVUTIL_LIBRARIES}
    ${SWSCALE_LIBRARIES}
    pthread
)

target_compile_options(desktop_viewer PRIVATE -O2 -Wall -Wextra)