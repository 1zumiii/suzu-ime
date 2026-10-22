# suzu-ime 推理核心
#
# 分层（下层不依赖上层）：
#   model/    Suzu 推理：词表、候选前缀树、llama.cpp 封装（单线程，无锁）
#   lexicon/  候选生成用的词典索引与拼音切分（只依赖词表，加载后只读）
#   runtime/  调度：独占模型的工作线程、带时限的请求、结果缓存、统计与日志
#   lua/      进程内 Lua 模块 rime_llm（macOS 鼠须管、Windows 小狼毫、Linux 测试）
#   ipc/      守护进程与客户端的线路协议
#   daemon/   Android 守护进程 rimellmd（监督进程 + 工作进程）
#   client/   Android 客户端库 librime_llm.so（对 Lua 暴露同名接口）
#   platform/ 平台相关的能力（Windows 的光标上文读取）
#   tools/    基准与对照工具
#
# 依赖的位置（默认是仓库旁边的 rime-llm-mac-port/，与旧构建一致）：
#   SUZU_LLAMA_DIR  llama.cpp 源码（固定在 b11632）
#   SUZU_LUA_DIR    Lua 5.4.6 源码的 src/ 目录
cmake_minimum_required(VERSION 3.20)
if(APPLE)
  # 鼠须管：Apple Silicon，macOS 13 起（必须在 project() 之前设置）
  set(CMAKE_OSX_ARCHITECTURES "arm64" CACHE STRING "")
  set(CMAKE_OSX_DEPLOYMENT_TARGET "13.0" CACHE STRING "")
endif()
project(suzu_core CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(SUZU_BUILD_LUA_MODULE "进程内 Lua 模块 rime_llm（桌面端）" ON)
# 守护进程用到 Linux 专有的接口（accept4、/proc），只在 Android 与 Linux 上构建；macOS、Windows 默认关闭
if(UNIX AND NOT APPLE)
  set(SUZU_DAEMON_DEFAULT ON)
else()
  set(SUZU_DAEMON_DEFAULT OFF)
endif()
option(SUZU_BUILD_DAEMON     "守护进程 rimellmd 与客户端库 librime_llm.so（Android，也可在 Linux 上做主机构建）" ${SUZU_DAEMON_DEFAULT})
option(SUZU_BUILD_TOOLS      "基准工具与测试用的 Lua 解释器" OFF)
option(SUZU_REPACK           "默认打开 llama.cpp 的 CPU 权重重排" OFF)
option(SUZU_TEST_HOOKS       "测量用：按环境变量 SUZU_SLOWDOWN 拉长每次解码（模拟低频设备）" OFF)

if(MSVC)
  # 源文件是 UTF-8（含中文注释）；MSVC 默认按系统代码页读取，会报 C4819
  add_compile_options(/utf-8)
endif()

include(cmake/deps.cmake)

# ---------------------------------------------------------------- 推理层
add_library(suzu_model STATIC
  model/vocab.cpp
  model/prefix_tree.cpp
  model/suzu_model.cpp)

# ---------------------------------------------------------------- 词典索引（候选生成用；只依赖词表）
add_library(suzu_lexicon STATIC lexicon/lexicon.cpp)
target_include_directories(suzu_lexicon PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_include_directories(suzu_model PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(suzu_model PUBLIC llama)
if(SUZU_TEST_HOOKS)
  target_compile_definitions(suzu_model PRIVATE SUZU_TEST_HOOKS=1)
endif()

# ---------------------------------------------------------------- 调度层
find_package(Threads REQUIRED)
add_library(suzu_runtime STATIC
  runtime/clock.cpp
  runtime/log.cpp
  runtime/stats.cpp
  runtime/engine.cpp)
target_link_libraries(suzu_runtime PUBLIC suzu_model suzu_lexicon Threads::Threads)
target_compile_definitions(suzu_runtime PRIVATE SUZU_REPACK_DEFAULT=$<BOOL:${SUZU_REPACK}>)

# ---------------------------------------------------------------- Lua 绑定的公共部分（进程内模块与客户端共用）
add_library(suzu_lua_common STATIC lua/lua_common.cpp)
target_include_directories(suzu_lua_common PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${SUZU_LUA_DIR})

# ---------------------------------------------------------------- 进程内模块 rime_llm
if(SUZU_BUILD_LUA_MODULE)
  if(WIN32)
    add_library(rime_llm SHARED lua/rime_llm_module.cpp platform/windows/ctxlink_win.cpp)
  elseif(APPLE)
    add_library(rime_llm MODULE lua/rime_llm_module.cpp platform/macos/ctxlink_mac.cpp)
    target_link_libraries(rime_llm PRIVATE "-framework ApplicationServices" "-framework CoreFoundation")
  else()
    add_library(rime_llm MODULE lua/rime_llm_module.cpp platform/ctxlink_stub.cpp)
  endif()
  set_target_properties(rime_llm PROPERTIES PREFIX "")
  if(NOT WIN32)
    set_target_properties(rime_llm PROPERTIES SUFFIX ".so")
  endif()
  target_link_libraries(rime_llm PRIVATE suzu_runtime suzu_lua_common)
  suzu_link_lua_host(rime_llm)
endif()

# ---------------------------------------------------------------- 守护进程与客户端
if(SUZU_BUILD_DAEMON)
  add_executable(rimellmd
    daemon/main.cpp
    daemon/options.cpp
    daemon/server.cpp
    daemon/supervisor.cpp
    daemon/status.cpp)
  target_include_directories(rimellmd PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(rimellmd PRIVATE suzu_runtime)
  if(ANDROID)
    target_link_libraries(rimellmd PRIVATE log)
    target_link_options(rimellmd PRIVATE -Wl,--gc-sections)
  endif()

  add_library(rime_llm_client MODULE client/rime_llm_client.cpp client/connection.cpp)
  set_target_properties(rime_llm_client PROPERTIES PREFIX "lib" OUTPUT_NAME "rime_llm" SUFFIX ".so"
                        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/client)
  target_include_directories(rime_llm_client PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(rime_llm_client PRIVATE suzu_lua_common Threads::Threads)
  suzu_link_lua_host(rime_llm_client)
endif()

# ---------------------------------------------------------------- 工具
if(SUZU_BUILD_TOOLS)
  add_executable(suzu_bench tools/bench_model.cpp)
  target_link_libraries(suzu_bench PRIVATE suzu_model)
  suzu_add_lua_interpreter(lua54)
endif()
