# macOS（鼠须管）

鼠须管在进程内加载推理核心 `rime_llm.so`，由 Lua 处理器 `ai_rerank_processor.lua` 与过滤器 `ai_rerank_filter.lua` 调用，用 Suzu 模型按上文重排候选。核心的源码、接口与构建见 [core/README.md](../core/README.md)；本目录只有安装脚本。

## 安装

先按 core/README.md 的 macOS 一节构建 `rime_llm.so`，再执行：

```bash
macos/install_llm.sh core/build/rime_llm.so <suzu.gguf> <suzu.vocab.txt>
```

脚本把 `rime_llm.so` 放进 `~/Library/Rime/lua/`，把模型与词表以 `suzu.gguf`、`suzu.vocab.txt` 的名字放进 `~/Library/Rime/`。处理器在用户目录里看到这两个文件就启用 Suzu；没有这两个文件时不重排，输入不受影响。也可用 `llm_rerank/student_model`、`llm_rerank/student_vocab` 指定路径，`llm_rerank/student: false` 关闭。Lua 脚本与方案配置由配置仓库的 `install.sh` 安装。

重启鼠须管后生效。AI 重排默认开启，`Control+Shift+5` 在当前输入框临时开关，也可在方案菜单（F4 或 ``Control+` ``）中选择“AI 关/开”。

## 不要原地覆盖 `rime_llm.so`

替换已存在的 `rime_llm.so` 时，须先删除再复制，`install_llm.sh` 已按此处理。原地覆盖会使 macOS 缓存的旧代码签名与新内容不符，加载时内核以 `SIGKILL (Code Signature Invalid)` 终止进程，输入法一启动即崩溃，反复崩溃后系统会把它从“已启用的输入法”中移除。

恢复方法：删除并重新复制 `.so`，然后运行 `Squirrel --disable-input-source im.rime.inputmethod.Squirrel.Hans`、`--enable-input-source …`、`--select-input-source …`；必要时在“系统设置 → 键盘 → 输入法”中重新添加。

## 为什么能直接加载

鼠须管的签名带 `disable-library-validation`，其 `librime-lua.dylib` 导出全部 `lua_*` 符号（内嵌 Lua 5.4.6），因此自行编译的 Lua C 模块可以直接加载，无需相同的开发者签名。核心以 `-undefined dynamic_lookup` 链接，运行时解析这些符号。

## 退出时的清理

核心在第一次加载模型之后注册进程退出时的清理函数：先停工作线程、释放模型，再让 ggml 的静态对象析构。模块被回收（`__gc`）时同样停止工作线程并释放模型。否则退出时若工作线程正在解码，进程会在 ggml 析构之后访问已释放的对象而崩溃。

## 排查

- 核心状态：在 Lua 里调用 `_G.ai_rerank_llm.status()`，返回 JSON，含加载状态、打分次数、跳过原因与等待时间分布。
- 日志：`~/Library/Rime/rime_llm_log.txt`，只记事件名、数字与模型路径，不含输入内容，超过 1 MB 改名为 `.old`。
- 过滤器的 `llm_rerank/debug_latency: true` 另写 `llm_latency.txt`，只含输入长度、毫秒数与原因码。

## Lua 层

`rime/lua/ai_rerank_filter.lua`、`ai_rerank_processor.lua` 移植自原项目的 `llm_filter.lua`、`llm_processor.lua`，含针对全拼的适配（按覆盖长度分组、四字两阶段）。与原项目相比的主要改动：

- 开关：方案开关 `ai_rerank`，默认开启（鼠须管的每个输入框为独立会话，默认关闭会使新输入框看起来未生效）。关闭时处理器直接放行，过滤器惰性透传，不加载模型。
- 不取尽候选：只取前 `scan_limit`（80）个候选重排，其余惰性透传。雾凇词库很大，输入单个字母时候选数以万计。
- 触发范围：默认只对 3 个字母及以上的输入触发（`code_pattern: [a-z]{3,}`），避开置顶词（`d`→的）和简拼。
- 上文：优先读前台应用里光标前的文字（辅助功能接口，`core/platform/macos/ctxlink_mac.cpp`，由 Lua 的 `ai_rerank_app_ctx.lua` 接入），读不到时用 Rime 的 `commit_history`。读应用文字需要在“系统设置 → 隐私与安全性 → 辅助功能”里给鼠须管授权，第一次使用时系统会弹出申请；重新安装 `rime_llm.so` 后若读不到，在该页把鼠须管关掉再打开。密码框不读。已在 TextEdit 验证；浏览器网页、Electron 应用能否读到未验证，读不到时自动回落。
- 隐私：重排本身不写入任何含输入内容的文件。读到的应用文字只在内存里，不写文件、不记日志。
- 第二阶段（四字 = 两个两字词）：退格时（输入串变短）不执行；剩余拼音少于 4 个字母时不执行；只用最后 8 个字作为上文；第一阶段按“上文 + 候选”缓存。第二阶段使用的翻译器存放在各引擎自己的 `env` 中，不在输入框之间共享（共享曾在引擎销毁后引发段错误）。
