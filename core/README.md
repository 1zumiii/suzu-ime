# core：推理核心

本目录是输入法候选重排的推理核心，只服务 Suzu 模型。它给 Rime 的 Lua 脚本提供 `rime_llm` 模块：macOS 鼠须管与 Windows 小狼毫在进程内加载，Android 同文输入法通过守护进程 `rimellmd` 与客户端库 `librime_llm.so` 使用。三端是同一份推理与调度代码。

设计说明见 [docs/design/core-design.md](../docs/design/core-design.md)，测量报告见 [docs/design/core-measurements.md](../docs/design/core-measurements.md)。

## 分层

下层不依赖上层。每层一个目录：

| 目录 | 职责 | 线程 |
|---|---|---|
| `model/` | Suzu 推理：字符词表（`vocab`）、候选前缀树（`prefix_tree`）、llama.cpp 封装与上文缓存（`suzu_model`） | 单线程，无锁 |
| `runtime/` | 调度：独占模型的工作线程与带时限的请求（`engine`）、结果缓存、统计、只含数字的日志 | 一个工作线程 |
| `lua/` | 进程内模块 `rime_llm` 的参数转换（`rime_llm_module`），以及与客户端共用的部分（`lua_common`） | 调用方线程 |
| `ipc/` | 守护进程与客户端的帧读写和协议说明 | |
| `daemon/` | 守护进程：参数（`options`）、工作进程（`server`）、监督进程（`supervisor`）、状态文件（`status`） | 每连接一个线程 |
| `client/` | 客户端库：连接管理（`connection`）与 Lua 接口（`rime_llm_client`） | 调用方线程 |
| `platform/` | 平台相关能力。Windows 的光标上文读取在 `platform/windows/`，其他平台为桩 | |
| `tools/` | 推理层基准与对照工具 `suzu_bench` | |

## Lua 接口

`rime/lua/` 与 `tests/` 依赖以下接口。新核心与旧核心兼容，扩展的部分标为“新增”。

### 函数

| 函数 | 行为 | 最长耗时 |
|---|---|---|
| `score(上文, 候选表, allow_sync)` | 返回按分数降序排列的候选表；不打分时返回 `nil, 原因` | 进程内：`allow_sync == false` 时 `budget_ms`（默认 40 ms），否则 `sync_budget_ms`（默认 70 ms）。客户端：各加 20 ms，默认 65 ms 与 90 ms |
| `get_scores()` | 最近一次成功打分的“候选 → 分数”表，分数为各字对数概率之和 | 立即返回 |
| `prepare(上文)` | 请求预解码，不阻塞。返回 `true` 表示已排上；模型未就绪时触发加载并返回 `false` | 进程内立即返回；客户端 50 ms |
| `is_ready()` | 模型是否就绪 | 进程内立即返回；客户端 50 ms，结果缓存 300 ms |
| `status()` | JSON 字符串，含 `"student":true`、状态与统计（新增统计字段） | 进程内立即返回；客户端 150 ms |
| `touch()` | 刷新“最近使用”时间（空闲卸载用） | 客户端 40 ms，1.5 秒最多一次 |
| `match_code(模式, 编码)` | 全串正则匹配；空模式恒为真，非法模式回退 `.{4}` | 立即返回 |
| `now_ms()`、`sleep_ms(毫秒)` | 单调时钟；测试用的睡眠（`sleep_ms` 为新增） | |
| `llm_context()`、`kick_context()`、`edit_reset_context()`、`fg_changed()`、`click_happened()` | 光标上文旁路。Windows 读取前台应用的光标前文字，其他平台恒为 `nil` 或 `false` | 立即返回 |

`score` 不打分时的原因码：

| 原因 | 含义 | 是否计为跳过 |
|---|---|---|
| `no_context` | 上文不足 2 个字 | 否 |
| `few_candidates` | 候选不足 2 个 | 否 |
| `loading` | 模型正在加载 | 是 |
| `no_model` | 未配置模型、文件缺失或加载失败（同一配置失败后 30 秒内不重试） | 是 |
| `off` | 手动关闭 | 是 |
| `timeout` | 时限内没算完。任务继续算完，结果进缓存，下一次同样的请求直接命中 | 是 |
| `error` | 推理出错，缓存已清空，下次自愈 | 是 |
| `down` | 客户端连不上守护进程 | 是 |

旧核心在上文不足 2 个字时返回不带原因的 `nil`，守护进程把它计入跳过。新核心给出原因 `no_context`，统计里单列。

### 属性

可写属性走 `__index`、`__newindex`，不能预先放进模块表。

| 属性 | 含义 |
|---|---|
| `model_path` | 模型文件。未设置 `student_vocab` 时按同名规则找词表（`suzu.gguf` → `suzu.vocab.txt`） |
| `student_vocab` | 词表文件 |
| `student_ctx` | 上文最多取最后几个字，默认 30 |
| `n_threads` | 推理线程数，默认 4，不超过核数减一 |
| `n_seq_max` | 旧口径：候选上限为 `n_seq_max - 1`，默认 12 |
| `idle_unload_sec` | 空闲多少秒后卸载模型，0 为不卸载（默认） |
| `use_repack` | llama.cpp 的 CPU 权重重排，加载前设置才生效 |
| `log_dir` | 日志目录，日志文件为 `rime_llm_log.txt` |
| `budget_ms`、`sync_budget_ms` | 新增：两种打分请求的时限 |
| `max_ctx`、`min_tokens`、`n_ctx` | 只为兼容而保留，对 Suzu 无作用 |

守护进程模式下，客户端另有 `set_enabled(开关)`、`screen_off(熄屏)`、`connected()`、`port` 属性，`backend` 为 `"daemon-client"`；进程内模块的 `backend` 为 `"inproc"`。

## 构建

依赖两份第三方源码，默认位置是仓库旁边的 `rime-llm-mac-port/`，可用 `-DSUZU_DEPS_ROOT=` 整体指定，或用 `-DSUZU_LLAMA_DIR=`、`-DSUZU_LUA_DIR=` 分别指定：

```
rime-llm-mac-port/
  llama.cpp/            llama.cpp，固定在标签 b11632
  deps/lua-5.4.6/src/   Lua 5.4.6 源码（sha256 7d5ea1b9cb6aa0b59ca3dde1c6adcb57ef83a1ba8e5432c0ecd06bf439b3ad88）
```

获取方式：

```bash
git clone --depth 1 --branch b11632 https://github.com/ggml-org/llama.cpp.git rime-llm-mac-port/llama.cpp
curl -LO https://www.lua.org/ftp/lua-5.4.6.tar.gz && tar xf lua-5.4.6.tar.gz -C rime-llm-mac-port/deps/
```

CMake 选项：

| 选项 | 默认 | 作用 |
|---|---|---|
| `SUZU_BUILD_LUA_MODULE` | ON | 进程内模块 `rime_llm.so`（Windows 为 `rime_llm.dll`） |
| `SUZU_BUILD_DAEMON` | Linux 与 Android 上 ON | 守护进程 `rimellmd` 与客户端 `client/librime_llm.so` |
| `SUZU_BUILD_TOOLS` | OFF | `suzu_bench` 与测试用的 Lua 解释器 `lua54` |
| `SUZU_LUA_HOST_LIB` | 空 | Android：Trime 的 `librime_jni.so`，客户端从它解析 `lua_*` |
| `SUZU_REPACK` | OFF | 默认打开 CPU 权重重排 |
| `SUZU_TEST_HOOKS` | OFF | 测量用：按环境变量 `SUZU_SLOWDOWN` 的倍数拉长每次解码，发布构建不要打开 |

### Linux（测试与测量）

```bash
cmake -G Ninja -S core -B core/build -DSUZU_BUILD_TOOLS=ON
cmake --build core/build
tests/run_all.sh core/build <suzu.gguf> <suzu.vocab.txt>
```

### macOS（鼠须管）

依赖 Xcode 命令行工具、CMake 与 Ninja。产物为 arm64 的 Mach-O bundle，`lua_*` 符号在运行时由鼠须管的 `librime-lua.dylib` 提供：

```bash
cmake -G Ninja -S core -B core/build
cmake --build core/build --target rime_llm
macos/install_llm.sh core/build/rime_llm.so <suzu.gguf> <suzu.vocab.txt>
```

安装脚本先删除旧的 `.so` 再复制并重新签名。原地覆盖会让 macOS 缓存的代码签名失效，鼠须管加载时会被系统终止，原因见 [macos/README.md](../macos/README.md)。

### Windows（小狼毫）

在“x64 Native Tools Command Prompt for VS 2022”中执行：

```bat
cmake -G Ninja -S core -B core\build -DCMAKE_BUILD_TYPE=Release
cmake --build core\build --target rime_llm
```

产物为 `core\build\rime_llm.dll`。小狼毫不导出 `lua_*`，因此与上游做法相同，Lua 源码编进 DLL。光标上文读取的代码在 `platform/windows/ctxlink_win.cpp`，移自上游。Lua 脚本使用上游的 `llm_filter.lua`、`llm_processor.lua` 加本仓库的 [windows/rime-llm-rerank-windows.patch](../windows/rime-llm-rerank-windows.patch)。

**Windows 构建未在本仓库的验证环境中编译过**，首次构建后须在小狼毫上确认：模块能加载，`llm_context()` 能读到光标前文字，重排生效。

### Android（同文输入法）

依赖 Android NDK r27d（`27.3.13750724`）。完整的 APK 构建由 [android/trime/build_apk.sh](../android/trime/build_apk.sh) 完成；只编守护进程与客户端：

```bash
cmake -G Ninja -S core -B build_android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DSUZU_BUILD_LUA_MODULE=OFF -DSUZU_LUA_HOST_LIB=<librime_jni.so>
cmake --build build_android --target rimellmd rime_llm_client
```

交叉编译固定使用 `armv8.6-a+dotprod+i8mm+fp16`（骁龙 8 Gen 2）。守护进程的命令行参数、令牌文件与状态文件的旧字段与旧版相同，改版 Trime 的启动脚本与 AI 徽标无需改动。

## 发布

1. 用发布配置构建（`SUZU_TEST_HOOKS` 保持 OFF）。
2. 在 Linux 或 macOS 上跑 `tests/run_all.sh`，结果须为 `ALL OK`，其中留出集首选一致率不低于 99.9%。
3. macOS：`macos/install_llm.sh` 安装后重启鼠须管，用 `status()` 确认 `"state":"ready"`。
4. Android：`android/trime/build_apk.sh` 出包并安装，点 AI 徽标启动守护进程，长按徽标确认在线；`ai_status.json` 的 `engine` 与 `client` 两个对象记录新核心的统计。
5. Windows：按上一节构建并实测。
6. 配置仓库 rime-ice-Custom 用 `tools/sync_core.sh` 同步 `rime/lua/`（本次重构未改 Lua 脚本）。
