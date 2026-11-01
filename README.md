# suzu-ime

为 Rime 输入法提供候选排序与生成的小模型推理核心，支持 macOS、Windows 和 Android。

简拼输入时，Rime 只按词库权重排列候选，目标词常常不在第一页。本项目使用 4000 万参数的字符级模型 [Suzu](https://github.com/1zumiii/Suzu)，根据光标前的文字为同音候选打分，并补充简拼中 Rime 未给出的词。模型文件 44 MB，三端均在本机运行，输入内容不出设备。

## 效果

测试方法：从留出集的句子里取一个词作为正确答案，在真实 librime 里逐键输入它的拼音或简拼，上文取答案之前的文字。表中的百分比是命中率：“首选”是正确答案排在第一位的题目占比，“前 5 名”是正确答案出现在前 5 个候选里的题目占比。题数为两字母简拼 407、混合输入 291、全拼 216，均为抽样。

| 输入 | 关 AI（Rime 原顺序） | Suzu-2.1 | Suzu-2.4 |
|---|---|---|---|
| 两字母简拼（如 `zm`），首选 | 10.7% | 41.8% | **49.4%** |
| 两字母简拼，前 5 名 | 32.8% | 73.7% | **77.9%** |
| 混合输入（如 `yangs`），首选 | 33.0% | 68.7% | **72.5%** |
| 全拼，首选 | 63.4% | **91.7%** | 90.7% |

全拼在 16000 题的维基评测上两个版本没有差别（+0.0，±0.3），表中全拼的 1 个点差别在 216 题的抽样误差内。留出集的答案偏冷门，真实输入多打常用词，实际收益以使用数据为准。

速度与内存：打分 p99 指 99% 的打分在此时间内完成；常驻内存指加载模型并稳态打分后的进程驻留集，含被映射的模型文件页（约 44 MB）。

默认配置下（统一排序加候选生成）的按键仿真，100 个词，Suzu-2.1 与 Suzu-2.4 的延迟没有可见差别：

| 平台 | 打分 p99 | 每次按键 p99 | 每次按键最大 | 常驻内存 |
|---|---|---|---|---|
| macOS，4 线程 | 10 到 12 ms | 21 到 23 ms | 28 到 52 ms | 约 118 MB |
| Windows，4 线程 | 6 到 19 ms | 22 到 23 ms | 25 到 28 ms | 待测 |
| Android（Zenfone 10），2 线程，经 adb | 17 到 37 ms | 35 到 39 ms | 49 到 55 ms | 约 81 MB |

每次按键的耗时包含 Lua 脚本与核心在输入法线程上的全部时间。Android 的数字经 adb 转发，不含手机上 Lua 的真实耗时，只能作参考。关闭候选生成时 Mac 的常驻内存约 66 到 72 MB。

每次调用均有时限：桌面最多等待 40 ms，手机约 65 ms。超时后保持 Rime 原顺序，不继续阻塞输入。完整数据和测量条件见 [技术报告](docs/technical-report.md)。

## 功能

- 重排：按上文为同一拼音的候选打分，调整顺序。
- 候选生成：简拼和混合输入时，从词典中按上文筛选 Rime 未给出的词，补充到候选窗。默认开启。
- 统一排序：总分 = Rime 名次先验 + 上文权重 × 模型分 + 个人习惯。关闭 AI 时上文权重为 0，其余项保持不变。
- 个人习惯：最近使用、词对和纠错记忆均只保存在本机。
- 输入事件流：默认关闭，开启后在本机记录选词、上屏与删除，用于校准参数。
- 功能状态页：改版 Trime 可显示 AI 服务、排序方式和各类记录的状态。

## 平台

三端共用 `core/` 里的同一份推理与调度代码。没有模型文件时不重排，输入不受影响。

| 平台 | 前端 | 运行方式 |
|---|---|---|
| macOS | 鼠须管 | 进程内加载 `rime_llm.so` |
| Windows | 小狼毫 0.17 | 进程内加载 `rime_llm.dll` |
| Android | 改版同文输入法（Trime） | 常驻守护进程 `rimellmd`，须经 adb 或 Shizuku 启动 |

## 快速开始

Android 使用改版 Trime，方案、Lua 和默认词库随 APK 提供，APK 可从[发布页](https://github.com/1zumiii/suzu-ime/releases/tag/android-v0.1.0)下载。安装后可在引导页下载模型，并通过 Shizuku 启动 AI 服务；词库和模型可在“设置 → 词库”中管理，见 [android/README.md](android/README.md)。

macOS 与 Windows 的安装步骤如下：

1. 从 Hugging Face 下载模型 [Suzu-2.4](https://huggingface.co/IzumiThe3rd/Suzu-2.4) 的 `suzu.gguf` 与 `suzu.vocab.txt`。
2. Rime 的配置、Lua 脚本和词库包使用 [rime-suzu](https://github.com/1zumiii/rime-suzu)，按其说明安装。
3. 按平台安装推理核心：
   - macOS：`macos/install_llm.sh core/build/rime_llm.so suzu.gguf suzu.vocab.txt`，详见 [macos/README.md](macos/README.md)
   - Windows：见 [windows/README.md](windows/README.md)
4. 核心的构建与接口见 [core/README.md](core/README.md)。

### 接入已有的 Rime 配置

已有 Rime 配置可直接接入。将 `rime/lua/` 下的 Lua 文件放入 Rime 用户目录的 `lua/`，将 `rime/suzu_ime.yaml` 放入用户目录，再在目标方案的 `<方案名>.custom.yaml` 中添加：

```yaml
patch:
  __include: suzu_ime:/patch
```

重新部署后，方案即接入处理器、过滤器、AI 开关和快捷键（Control+Shift+5）。引用须放在 `patch` 下，并使用 `__include`。若使用 `__patch`，会替换方案原有的整个处理器与过滤器列表；若写在文件顶层，则不生效。

Lua 默认值已采用调优后的参数：2 个字母起重排、参与重排的候选 10 个、统一排序的名次先验系数 0.05、首位门控 2.5，无需复制 rime-suzu 的配置。需要调整时，在同一个 `patch` 下用 `llm_rerank/...` 覆盖。可用配置键见 `rime/lua/ai_rerank_filter.lua` 与 `rank_unified.lua` 开头的说明。

## 文档

| 文档 | 内容 |
|---|---|
| [技术报告](docs/technical-report.md) | 各平台延迟与内存、候选生成对照、未验证项目 |
| [设计说明](docs/design/core-design.md) | 推理核心的结构与取舍 |
| [测量报告](docs/design/core-measurements.md) | 新旧核心对照、优化分项与故障注入 |
| [个人化](docs/personalization.md) | 个人习惯的记录与使用 |
| [多模型](docs/multi-model.md) | 多模型设计草案 |

## 目录

| 路径 | 内容 |
|---|---|
| `core/` | 推理核心：推理层、调度层、Lua 模块、守护进程与客户端 |
| `rime/lua/` | Rime 的 Lua 过滤器与处理器 |
| `rime/suzu_ime.yaml` | 方案补丁：把上述组件接到任意拼音方案上 |
| `android/` | 改版 Trime 源码、APK 构建脚本、守护进程部署脚本 |
| `macos/`、`windows/` | 各平台的安装、部署与回滚脚本 |
| `tests/` | 全部测试，`tests/run_all.sh` 一键运行 |
| `tools/` | 按键仿真、选词语料分析、离线回放、链路审计、基准 |

## 相关仓库

- 模型与训练：[Suzu](https://github.com/1zumiii/Suzu)。
- 配置与词库包：[rime-suzu](https://github.com/1zumiii/rime-suzu)，其中的重排 Lua 由本仓库的 `rime/lua/` 同步而来。
- 原项目：本项目起步于 [rime-llm-rerank](https://github.com/zhanghaozhecn/rime-llm-rerank)，推理核心已重写，Windows 的光标上文读取和部分 Lua 仍取自该项目。Android 前端硬分叉自 [osfans/trime](https://github.com/osfans/trime)。署名见 [NOTICE.md](NOTICE.md)。

## 许可

代码按 GPL-3.0 许可（[LICENSE](LICENSE)）。模型权重的许可见 Suzu 仓库。
