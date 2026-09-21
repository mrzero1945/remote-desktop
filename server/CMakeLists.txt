cmake_minimum_required(VERSION 3.16)
project(RemoteDesktopServer VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(PkgConfig REQUIRED)
find_package(Boost REQUIRED)
pkg_check_modules(X11 REQUIRED x11 xext xfixes xtst)
pkg_check_modules(AVFORMAT REQUIRED libavformat)
pkg_check_modules(AVCODEC REQUIRED libavcodec)
pkg_check_modules(AVUTIL REQUIRED libavutil)
pkg_check_modules(SWSCALE REQUIRED libswscale)
pkg_check_modules(PULSESIMPLE REQUIRED libpulse-simple)
pkg_check_modules(PULSE REQUIRED libpulse)
pkg_check_modules(OPUS REQUIRED opus)
find_package(OpenSSL REQUIRED)

add_executable(remote_desktop_server
    src/main.cpp
    src/screen_capture.cpp
    src/encoder.cpp
    src/udp_server.cpp
    src/input_handler.cpp
    src/crypto.cpp
    src/audio_capture.cpp
)

target_include_directories(remote_desktop_server PRIVATE
    ${X11_INCLUDE_DIRS}
    ${AVFORMAT_INCLUDE_DIRS}
    ${AVCODEC_INCLUDE_DIRS}
    ${AVUTIL_INCLUDE_DIRS}
    ${SWSCALE_INCLUDE_DIRS}
    ${PULSESIMPLE_INCLUDE_DIRS}
    ${PULSE_INCLUDE_DIRS}
    ${OPUS_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(remote_desktop_server
    Boost::boost
    ${X11_LIBRARIES}
    ${AVFORMAT_LIBRARIES}
    ${AVCODEC_LIBRARIES}
    ${AVUTIL_LIBRARIES}
    ${SWSCALE_LIBRARIES}
    ${PULSESIMPLE_LIBRARIES}
    ${PULSE_LIBRARIES}
    ${OPUS_LIBRARIES}
    OpenSSL::SSL
    OpenSSL::Crypto
    pthread
)

target_compile_options(remote_desktop_server PRIVATE -O3 -march=native -Wall -Wextra)
