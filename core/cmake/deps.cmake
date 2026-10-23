# 第三方依赖：llama.cpp（静态链接、只用 CPU 后端）与 Lua 5.4 头文件/源码。

set(SUZU_DEPS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../rime-llm-mac-port" CACHE PATH "第三方源码所在目录")
set(SUZU_LLAMA_DIR "${SUZU_DEPS_ROOT}/llama.cpp" CACHE PATH "llama.cpp 源码（b11632）")
set(SUZU_LUA_DIR   "${SUZU_DEPS_ROOT}/deps/lua-5.4.6/src" CACHE PATH "Lua 5.4.6 的 src/ 目录")

if(NOT EXISTS "${SUZU_LLAMA_DIR}/include/llama.h")
  message(FATAL_ERROR "找不到 llama.cpp：${SUZU_LLAMA_DIR}（用 -DSUZU_LLAMA_DIR=... 指定）")
endif()
if(NOT EXISTS "${SUZU_LUA_DIR}/lua.h")
  message(FATAL_ERROR "找不到 Lua 源码：${SUZU_LUA_DIR}（用 -DSUZU_LUA_DIR=... 指定）")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # 用 ggml 自己的线程池；OpenMP 在输入法宿主里不可控
set(GGML_METAL OFF CACHE BOOL "" FORCE)       # Suzu 用 CPU 更快（旧核心实测）
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
if(ANDROID)
  # 交叉编译不能用 -mcpu=native。基线不带 i8mm：没有 i8mm 的手机（约 2022 年以前的芯片）一运行就崩溃，
  # 而在骁龙 8 Gen 2 上实测，带 i8mm 与否打分延迟没有可见差别
  set(SUZU_ANDROID_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "Android 的指令集")
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  set(GGML_CPU_ARM_ARCH "${SUZU_ANDROID_ARM_ARCH}" CACHE STRING "" FORCE)
elseif(WIN32)
  # 发给别人的 DLL 不能用 GGML_NATIVE：在 Ryzen 9700X 上会编出 AVX-512，
  # 不支持的 CPU 上小狼毫服务进程直接崩溃。基线定为 AVX2（约 2013 年后的 Intel、2015 年后的 AMD），实测慢 3% 到 6%
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  set(GGML_AVX ON CACHE BOOL "" FORCE)
  set(GGML_AVX2 ON CACHE BOOL "" FORCE)
  set(GGML_FMA ON CACHE BOOL "" FORCE)
  set(GGML_F16C ON CACHE BOOL "" FORCE)
  set(GGML_AVX512 OFF CACHE BOOL "" FORCE)
elseif(APPLE)
  # 同理，-mcpu=native 换到更新的芯片上编会用上 M1 没有的指令（如 i8mm），基线定为 M1
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  set(GGML_CPU_ARM_ARCH "armv8.4-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory(${SUZU_LLAMA_DIR} llama EXCLUDE_FROM_ALL)

# Lua 的核心源码（不含解释器与编译器的 main）
file(GLOB SUZU_LUA_CORE_SRC ${SUZU_LUA_DIR}/l*.c)
list(FILTER SUZU_LUA_CORE_SRC EXCLUDE REGEX "/(lua|luac)\\.c$")

# Lua C 模块里的 lua_* 符号由谁提供：
#  - macOS 鼠须管：librime-lua.dylib 导出全部 lua_*，运行时解析（-undefined dynamic_lookup）
#  - Linux 测试：lua 解释器导出 lua_*（-z undefs）
#  - Android Trime：链接 Trime 的 librime_jni.so（SUZU_LUA_HOST_LIB），让加载器把 lua_* 解析到它
#  - Windows 小狼毫：宿主不导出 lua_*，与原项目做法相同，把 Lua 源码编进 DLL
set(SUZU_LUA_HOST_LIB "" CACHE FILEPATH "导出 lua_* 的宿主库（例如 Trime 的 librime_jni.so）")
function(suzu_link_lua_host target)
  if(WIN32)
    target_sources(${target} PRIVATE ${SUZU_LUA_CORE_SRC})
    target_compile_definitions(${target} PRIVATE LUA_USE_WINDOWS)
    if(MSVC)
      target_compile_options(${target} PRIVATE /utf-8)
    endif()
  elseif(APPLE)
    target_link_options(${target} PRIVATE -undefined dynamic_lookup)
  elseif(SUZU_LUA_HOST_LIB)
    target_link_libraries(${target} PRIVATE ${SUZU_LUA_HOST_LIB})
  else()
    target_link_options(${target} PRIVATE -Wl,-z,undefs)
  endif()
endfunction()

# 测试用的 Lua 解释器（导出 lua_*，可加载上面的 C 模块）
function(suzu_add_lua_interpreter target)
  add_executable(${target} ${SUZU_LUA_DIR}/lua.c ${SUZU_LUA_CORE_SRC})
  if(APPLE)
    target_compile_definitions(${target} PRIVATE LUA_USE_MACOSX)
  elseif(UNIX)
    target_compile_definitions(${target} PRIVATE LUA_USE_LINUX)
    target_link_libraries(${target} PRIVATE m ${CMAKE_DL_LIBS})
    target_link_options(${target} PRIVATE -Wl,-E)
  endif()
  set_target_properties(${target} PROPERTIES C_VISIBILITY_PRESET default)
endfunction()
