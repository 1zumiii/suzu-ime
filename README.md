# suzu-ime

基于 Rime 的本地 AI 拼音输入法：包含 Android App（改版同文输入法）和 macOS、Windows 共用的推理核心，按上文重排候选、补全简拼。

Android 版可从 [发布页](https://github.com/1zumiii/suzu-ime/releases/latest) 下载。其他平台的配置和安装包见 [rime-suzu](https://github.com/1zumiii/rime-suzu)。

本项目使用字符级模型 Suzu-UER6-KD500，根据光标前的文字为同音候选打分，并补充 Rime 未给出的简拼候选。模型文件约 52.8 MB，三端均在本机推理，输入内容不出设备。

## 效果

同一批固定候选只按模型分排序，首选准确率为正确答案排在第一位的题目占比：

| 评测 | 题数 | Suzu-UER6-KD500 | Suzu-2.4 |
|---|---:|---:|---:|
| 两字母简拼，每题 48 个候选 | 2,400 | 59.5% | 41.5% |
| 全拼同音词，每题最多 5 个候选 | 1,293 | 92.81% | 92.73% |

在鼠须管的 librime 中逐键采集候选，再用记录的候选和分数回算排序。按帖子划分的两字母简拼验证题上，首选准确率如下：

| 设置 | 题数 | 首选 |
|---|---:|---:|
| 不用模型分（同一批候选按 Rime 原名次排序） | 1,190 | 28.6% |
| Suzu-2.4，门槛 2.5（另一轮评测，未逐题配对） | 1,192 | 39.3% |
| Suzu-UER6-KD500，门槛 1.5（默认） | 1,190 | **54.0%** |

评测条件见 [KD500 模型卡](https://huggingface.co/IzumiThe3rd/Suzu-UER6-KD500)。

延迟与内存：

| 平台 | 条件 | 相对 Suzu-2.4 |
|---|---|---|
| Android | ASUS Zenfone 10，守护进程 2 线程，每组 10 个候选 | 每次打分多 1.5～2 ms，打分加生成的 P99 约 20 ms，常驻内存约 151 MB（Suzu-2.4 约 109 MB） |
| Windows | 桌面 4 线程，每题 10 个候选 | 上文已缓存时 P50 / P99 为 5.3 / 7.3 ms（Suzu-2.4 为 4.9 / 7.1 ms），现场解码上文时为 9.3 / 21.8 ms（Suzu-2.4 为 8.8 / 20.7 ms） |

推理请求设有等待时限，超时后当次输入继续使用已有候选。各平台的按键仿真见 [技术报告](docs/technical-report.md)。

Android 真实使用中的打分耗时（同一台 ASUS Zenfone 10，取自守护进程的统计）：

| | 旧核心 | 新核心，优化前 | 新核心，优化后 |
|---|---:|---:|---:|
| 中位数 | 17～30 ms | 8.5 ms | 6.6 ms |
| p99 | 未统计 | 35.9 ms | 31.1 ms |
| 最大 | 105 ms | 62.6 ms | 41.4 ms |

优化后一段用的是 KD500，其余两段是 Suzu 字符级模型。各段的条件和优化内容见 [技术报告：Android 真实使用对比](docs/technical-report.md#android-真实使用对比)。

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

### macOS 与 Windows

安装整套输入法（配置、词库、模型和推理核心）请用 [rime-suzu](https://github.com/1zumiii/rime-suzu#安装) 的一行安装命令，无需自己编译。

### Android

从 [发布页](https://github.com/1zumiii/suzu-ime/releases/latest) 下载 APK，支持 arm64、Android 11 及以上。

1. 安装后按引导启用并选择输入法，这时已经可以正常打字。
2. 在引导页或“设置 → 模型”下载打分模型，默认选 Suzu-UER6-KD500。
3. 安装并启动 [Shizuku](https://shizuku.rikka.app/)，再点工具栏的 AI 徽标启动 AI 服务。手机重启后要先启动 Shizuku，再启动 AI 服务。

词库在“设置 → 词库”中切换，详见 [Android 说明](android/README.md)。

从旧版本覆盖安装时，用户词典、设置和已下载的模型都会保留。已下载的 Suzu-2.4 不会自动换成 KD500，要换时在“设置 → 模型”中删除旧模型再下载。之前装的是其他签名的同文输入法时，须先卸载才能安装，卸载会清空用户词典，请先备份手机上的 `Android/data/com.osfans.trime/files/rime` 目录。

### 手动安装推理核心

已有自己的 Rime 配置，或想自己编译时，按以下步骤安装：

1. 下载 [Suzu-UER6-KD500](https://huggingface.co/IzumiThe3rd/Suzu-UER6-KD500) 的 GGUF 权重和配套词表，分别保存为 `suzu.gguf` 和 `suzu.vocab.txt`。两个文件要成对使用，换模型时一起替换。
2. 按平台安装推理核心：
   - macOS：`macos/install_llm.sh core/build/rime_llm.so suzu.gguf suzu.vocab.txt`，详见 [macos/README.md](macos/README.md)
   - Windows：见 [windows/README.md](windows/README.md)
3. 核心的构建与接口见 [core/README.md](core/README.md)。

### 接入已有的 Rime 配置

先按上一节装好推理核心、权重和词表，再接入方案。将 `rime/lua/` 下的 Lua 文件放入 Rime 用户目录的 `lua/`，将 `rime/suzu_ime.yaml` 放入用户目录，再在目标方案的 `<方案名>.custom.yaml` 中添加：

```yaml
patch:
  __include: suzu_ime:/patch
```

重新部署后，方案即接入处理器、过滤器、AI 开关和快捷键（Control+Shift+5）。引用须放在 `patch` 下，并使用 `__include`。若使用 `__patch`，会替换方案原有的整个处理器与过滤器列表。若写在文件顶层，则不生效。

Lua 从 2 个字母起重排，每组打分 10 个候选，统一排序的名次先验系数为 0.05。开启候选生成时，把每组放大到 20 或 30 个候选，Suzu-2.4 在 1,600 道两字母简拼题上的首选没有变化（34.7%、34.6%、34.6%），手机上每多一个候选约多 0.5 ms，所以保持 10 个。KD500 的首位门控默认值为 1.5。继续使用 Suzu-2.4 时，可在同一个 `patch` 下将 `llm_rerank/unified/top_gate` 设为 2.5。其他配置键见 `rime/lua/ai_rerank_filter.lua` 和 `rank_unified.lua`。

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
