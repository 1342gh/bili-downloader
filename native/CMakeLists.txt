# C++ 核心：B 站接口 / 下载 / WBI 签名，宿主机和安卓共用同一份代码。
#
# 宿主机构建（验证用）：
#   cmake -S . -B build-host -G "MinGW Makefiles" && cmake --build build-host
# 安卓构建由 D:\android-build\build.ps1 调用 NDK 的 CMake 工具链完成。
cmake_minimum_required(VERSION 3.16)
project(bili_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(bili_core STATIC
    src/md5.cpp
    src/wbi.cpp
    src/bili.cpp
    src/download.cpp
    src/ffmpeg_cmd.cpp
)
target_include_directories(bili_core PUBLIC src third_party)

if (WIN32)
    target_sources(bili_core PRIVATE src/http_winhttp.cpp)
    target_link_libraries(bili_core PUBLIC winhttp)
elseif (ANDROID)
    # 安卓端走 JNI 回调（http_jni.cpp 在移植阶段加入）
    target_sources(bili_core PRIVATE src/http_jni.cpp)
else()
    # 宿主机（Linux/macOS/WSL）测试：调系统 curl，零依赖
    target_sources(bili_core PRIVATE src/http_curl_cli.cpp)
endif()

if (NOT ANDROID)
    add_executable(bili_probe tools/bili_probe.cpp)
    target_link_libraries(bili_probe PRIVATE bili_core)
endif()
