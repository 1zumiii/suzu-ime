# suzu-ime

本仓库是输入法候选 AI 重排的推理核心及其在各平台的集成：给定上文和同一拼音下的候选词，用小模型 [Suzu](https://github.com/1zumiii/Suzu) 打分排序，由 Rime 前端（macOS 鼠须管、Windows 小狼毫、Android 同文输入法）调用。

模型权重发布在 Hugging Face（[Suzu-2.1](https://huggingface.co/IzumiThe3rd/Suzu-2.1)），不在本仓库中。雾凇拼音的配置与词库在 [rime-ice-Custom](https://github.com/1zumiii/rime-ice-Custom)，它引用本仓库的发布。

## 状态

推理核心正在重构。现有实现（`legacy` 部分）由 `macos/port_to_mac.py` 对上游 rime-llm-rerank 的 C++ 源码打补丁，再用 `macos/CMakeLists.txt` 和 `android/CMakeLists.txt` 构建，三端可用。新核心的落点是 `core/`，设计要求见 `docs/design/`。新核心通过全部测试后，旧的移植脚本会删除。

## 目录

| 路径 | 内容 |
|---|---|
| `core/` | 新推理核心（待实现），接口约定见 `core/README.md` |
| `rime/lua/` | Rime 的 Lua 过滤器与处理器：重排、个人词对、选词统计与语料归档、最近使用优先 |
| `android/` | 推理守护进程与客户端库、改版 Trime 补丁（`trime/patches/`）、构建与签名脚本 |
| `macos/` | 现有移植脚本、构建配置、安装脚本 |
| `windows/` | 小狼毫的补丁（基于上游 rime-llm-rerank 的固定提交） |
| `tests/` | 在模拟 Rime 环境中的端到端测试、守护进程测试、选词统计测试 |
| `tools/` | 选词统计汇总、守护进程基准等脚本 |
| `docs/` | 个人化说明、多模型设计草案、设计要求 |

## 与其他仓库的关系

- 模型与训练：[Suzu](https://github.com/1zumiii/Suzu)。
- 配置与词库：[rime-ice-Custom](https://github.com/1zumiii/rime-ice-Custom)，其中的重排 Lua 文件由本仓库的 `rime/lua/` 同步而来。手机端的覆盖配置和主题补丁也在那里（`llm-android/trime/`），改版 Trime 的“从仓库更新”按该路径读取。
- 上游：推理核心源自 [rime-llm-rerank](https://github.com/zhanghaozhecn/rime-llm-rerank)，Trime 补丁基于 [osfans/trime](https://github.com/osfans/trime)，见 `NOTICE.md`。

## 测试

测试脚本在 `tests/`，用法写在各文件开头的注释里。多数需要自行编译的 `rime_llm.so` 和 Suzu 模型文件，构建步骤见 `macos/README.md` 与 `android/README.md`。

## 许可

代码按 GPL-3.0 许可（`LICENSE`）。模型权重的许可见 Suzu 仓库。
