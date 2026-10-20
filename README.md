# suzu-ime

让 Rime 输入法按上文挑候选的小模型推理核心，覆盖 macOS、Windows 和 Android。

打简拼时，Rime 只按词库权重排候选，答案常常不在第一页。本项目用 4000 万参数的字符级模型 [Suzu](https://github.com/1zumiii/Suzu)，读取光标前的文字，给同一拼音下的候选打分，并在简拼里补出 Rime 没有给的词。模型文件 44 MB，三端都在本机运行，输入内容不出设备。

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

| 平台 | 打分 p99 | 常驻内存 |
|---|---|---|
| macOS，4 线程 | 9.1 ms | 72 MB |
| Windows，4 线程 | 4.7 ms | 70 MB |
| Android（Zenfone 10），2 线程 | 19.3 ms | 61 MB |

每次调用都有时限，桌面最多等 40 ms，手机约 65 ms。没算完就保持 Rime 原顺序，输入不会卡住。完整数据和测量条件见 [技术报告](docs/technical-report.md)。

## 功能

- **重排：** 按上文给同一拼音的候选打分，调整顺序。
- **候选生成：** 简拼和混合输入时，模型在词典里按上文挑出 Rime 没给的词，补进候选窗。默认开启。
- **统一排序：** 总分 = Rime 名次先验 + 上文权重 × 模型分 + 个人习惯。关闭 AI 时上文权重为 0，其余照常。
- **个人习惯：** 最近使用、词对和纠错记忆都只存在本机。
- **输入事件流：** 默认关闭，开启后记录选词、上屏与删除，只在本机，用于校准参数。
- **模块状态页：** 手机的改版 Trime 里可查看守护进程、排序方式和各类记录的状态。

## 平台

三端共用 `core/` 里的同一份推理与调度代码。没有模型文件时不重排，输入不受影响。

| 平台 | 前端 | 运行方式 |
|---|---|---|
| macOS | 鼠须管 | 进程内加载 `rime_llm.so` |
| Windows | 小狼毫 0.17 | 进程内加载 `rime_llm.dll` |
| Android | 改版同文输入法（Trime） | 常驻守护进程 `rimellmd` |

## 快速开始

1. 从 Hugging Face 下载模型 [Suzu-2.4](https://huggingface.co/IzumiThe3rd/Suzu-2.4) 的 `suzu.gguf` 与 `suzu.vocab.txt`。
2. 雾凇拼音的配置、词库和 Lua 脚本使用 [rime-ice-Custom](https://github.com/1zumiii/rime-ice-Custom)，按其说明安装。
3. 按平台安装推理核心：
   - macOS：`macos/install_llm.sh core/build/rime_llm.so suzu.gguf suzu.vocab.txt`，详见 [macos/README.md](macos/README.md)
   - Windows：见 [windows/README.md](windows/README.md)
   - Android：见 [android/README.md](android/README.md)
4. 核心的构建与接口见 [core/README.md](core/README.md)。

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
| `android/` | 改版 Trime 补丁、APK 构建脚本、守护进程部署脚本 |
| `macos/`、`windows/` | 各平台的安装、部署与回滚脚本 |
| `tests/` | 全部测试，`tests/run_all.sh` 一键运行 |
| `tools/` | 按键仿真、选词语料分析、离线回放、链路审计、基准 |

## 相关仓库

- 模型与训练：[Suzu](https://github.com/1zumiii/Suzu)。
- 配置与词库：[rime-ice-Custom](https://github.com/1zumiii/rime-ice-Custom)，其中的重排 Lua 由本仓库的 `rime/lua/` 同步而来。
- 上游：推理核心源自 [rime-llm-rerank](https://github.com/zhanghaozhecn/rime-llm-rerank)，Trime 补丁基于 [osfans/trime](https://github.com/osfans/trime)。署名见 [NOTICE.md](NOTICE.md)。

## 许可

代码按 GPL-3.0 许可（[LICENSE](LICENSE)）。模型权重的许可见 Suzu 仓库。
