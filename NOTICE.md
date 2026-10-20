# 声明与致谢

## 推理核心

本仓库起步于 [rime-llm-rerank](https://github.com/zhanghaozhecn/rime-llm-rerank)（作者 zhanghaozhecn）。该项目按自定的署名许可发布：允许自由使用、修改和分发，包括商用；商用时须在分发物中注明作者、项目名称与仓库地址。

`core/` 是重写后的核心：推理、调度与守护进程为新写的代码，Lua 接口、`match_code` 的语义与打分口径沿用原项目。以下部分直接取自原项目，文件开头注明了出处：

- `core/platform/windows/ctxlink_win.cpp`：Windows 的光标上文读取（COM 与 UIA）、前台切换与点击检测。
- `rime/lua/ai_rerank_filter.lua`、`rime/lua/ai_rerank_processor.lua`：移植自原项目的 `llm_filter.lua`、`llm_processor.lua`。
- `windows/rime-llm-rerank-windows.patch`：对原项目 Lua 脚本的补丁。

## Android 端

`android/trime/patches/` 中的补丁基于 [osfans/trime](https://github.com/osfans/trime)（GPL-3.0）的固定提交，改版后的 APK 同样按 GPL-3.0 分发。

## 推理引擎

推理使用 [llama.cpp](https://github.com/ggml-org/llama.cpp)（MIT），以静态链接方式编入 `rime_llm.so`、守护进程与 Windows 的 DLL。

## 输入法配置

Lua 过滤器与 Rime 的配置约定来自雾凇拼音 [rime-ice](https://github.com/iDvel/rime-ice)（GPL-3.0）。

## 模型

Suzu 模型由 Qwen3.5 系列蒸馏得到，权重按 CC BY-SA 4.0 发布，数据来源与署名见 [Suzu](https://github.com/1zumiii/Suzu) 仓库的 `NOTICE.md`。
