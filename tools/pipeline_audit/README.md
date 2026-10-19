# 整条输入链路的离线审计

用真实的 librime 加载一份用户目录，按留出集的上文与答案逐键输入，比较 AI 重排关闭与开启时的首选。核心的逐题对照（`tests/parity`）只测模型分，本工具测的是 Rime 候选生成、各类过滤器与重排合在一起的结果。

## 依赖

- librime 与 librime-lua 插件。macOS 用鼠须管自带的 `librime.1.dylib` 与 `rime-plugins/librime-lua.dylib`，librime 的头文件（`rime_api.h`）需另取。
- 一份用户目录的副本：含方案、词库、`lua/`（含新核心 `rime_llm.so`）、`suzu.gguf` 与 `suzu.vocab.txt`。不要直接用真实的用户目录，探测会写入用户词典与统计文件。
- 留出集 `cases.tsv` 与 `meta.json`（不在仓库内）。

## 测试钩子

过滤器的上文来自输入法对上屏历史的记录，离线探测没有这份历史。在用户目录副本的 `ai_rerank_filter.lua` 里，把读取上文的一行：

```lua
if _G.ai_rerank_context_get then ctx_text = _G.ai_rerank_context_get() end
```

改成读文件：

```lua
do local f = io.open("/tmp/ctx.txt", "r") if f then ctx_text = f:read("*a") f:close() end end
```

探测程序每个用例前写入 `/tmp/ctx.txt`。只改副本，不要改仓库里的文件。

## 步骤

```bash
python3 -I build_cases.py <cn_dicts 目录> <cases.tsv> <meta.json> out
clang -I<librime 头文件目录> probe.c -o probe <librime.1.dylib 路径>
DYLD_LIBRARY_PATH=<librime 所在目录> RIME_USER=<用户目录副本> ./probe out/full.tsv > full_out.tsv
PINYIN_JSON=out/pinyin.json SUZU_VOCAB=<suzu.vocab.txt> python3 -I analyze.py full_out.tsv
```

环境变量 `RIME_SHARED`、`RIME_USER`、`RIME_LUA_PLUGIN` 分别指定共享数据目录、用户目录和 Lua 插件，默认值为鼠须管的安装位置与当前目录下的 `ud`。`SKIP_N=1` 只跑 AI 开启一遍，用于比较参数。完整拼音 1293 题约 15 分钟，不完整输入再加约 15 分钟。

## 已有结果

2026-10-19 在 Mac 上的结果（留出集 1293 题，干净上文）：

| 输入 | 无 AI 首选准确率 | 有 AI 首选准确率 |
|---|---|---|
| 完整拼音 | 66.7% | 90.4% |
| 首音节全拼加其余声母 | 33.7% | 61.3% |

完整拼音下，由对变错的有 29 题，由错变对的有 335 题。审计发现并修复了两处系统性来源：词表外字的候选被打成高分排到首位，以及 comment 为空的用户词典候选无法识别模糊音。模糊音扣分按此工具的对照由 1.0 调到 3.0。
