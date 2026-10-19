# suzu-ime

本仓库是输入法候选 AI 重排的推理核心及其在各平台的集成：给定上文和同一拼音下的候选词，用小模型 [Suzu](https://github.com/1zumiii/Suzu) 打分排序，由 Rime 前端（macOS 鼠须管、Windows 小狼毫、Android 同文输入法）调用。

模型权重发布在 Hugging Face（[Suzu-2.1](https://huggingface.co/IzumiThe3rd/Suzu-2.1)），不在本仓库中。雾凇拼音的配置与词库在 [rime-ice-Custom](https://github.com/1zumiii/rime-ice-Custom)，它引用本仓库的发布。

## 状态

推理核心已重构为 `core/`，三端（鼠须管进程内、小狼毫进程内 DLL、同文守护进程）共用同一份代码，只服务 Suzu。旧的移植脚本与构建配置已删除。需求、设计说明与测量报告见 `docs/design/`。主机上的测试全部通过，真机上待验证的项目见设计说明第 6 节。

## 目录

| 路径 | 内容 |
|---|---|
| `core/` | 推理核心：推理层、调度层、Lua 模块、守护进程与客户端，接口与构建见 `core/README.md` |
| `rime/lua/` | Rime 的 Lua 过滤器与处理器：重排、个人词对、选词统计与语料归档、最近使用优先 |
| `android/` | 改版 Trime 补丁（`trime/patches/`）、APK 构建与签名脚本、守护进程的部署脚本 |
| `macos/` | 安装脚本与鼠须管说明 |
| `windows/` | 小狼毫的 Lua 补丁（基于上游 rime-llm-rerank 的固定提交） |
| `tests/` | 模拟 Rime 环境中的端到端测试、按键仿真、留出集对照、守护进程测试、选词统计测试 |
| `tools/` | 选词统计汇总、守护进程基准等脚本 |
| `docs/` | 个人化说明、多模型设计草案、设计要求 |

## 与其他仓库的关系

- 模型与训练：[Suzu](https://github.com/1zumiii/Suzu)。
- 配置与词库：[rime-ice-Custom](https://github.com/1zumiii/rime-ice-Custom)，其中的重排 Lua 文件由本仓库的 `rime/lua/` 同步而来。手机端的覆盖配置和主题补丁也在那里（`llm-android/trime/`），改版 Trime 的“从仓库更新”按该路径读取。
- 上游：推理核心源自 [rime-llm-rerank](https://github.com/zhanghaozhecn/rime-llm-rerank)，Trime 补丁基于 [osfans/trime](https://github.com/osfans/trime)，见 `NOTICE.md`。

## 测试

测试脚本在 `tests/`，`tests/run_all.sh` 在 Linux 或 macOS 上一次跑完，见 `tests/README.md`。多数测试需要自行编译的核心与 Suzu 模型文件，构建步骤见 `core/README.md`。

## 许可

代码按 GPL-3.0 许可（`LICENSE`）。模型权重的许可见 Suzu 仓库。
