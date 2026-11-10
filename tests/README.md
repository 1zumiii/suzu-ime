# 测试

全部测试都能在 Linux 或 macOS 主机上运行，不需要真机：

```bash
tests/run_all.sh <core 的构建目录> <suzu.gguf> <suzu.vocab.txt> [--quick]
```

构建目录须含 `rime_llm.so`；含 `rimellmd` 时一并测试守护进程（守护进程只在 Linux 上构建）。Lua 解释器优先用构建目录里的 `lua54`（`-DSUZU_BUILD_TOOLS=ON`）。构建方法见 `core/README.md`。

在 4 核 Linux 主机上，依次运行全部测试约需 50 秒（以前约 2 分钟）；6 核以上并行运行时耗时更短。测试的并行与超时策略如下：

- Lua 测试、留出集对照、守护进程测试三组互不依赖，在 6 核及以上的机器上默认并行，核数更少时依次运行；`--parallel`、`--serial` 可强制指定。Lua 测试组里有计时检查，组内始终依次运行。
- 功能检查（排序是否正确、两阶段是否运行）用宽松的时限，机器忙时不会因为超时而误报；时限本身由专门的检查验证（`filter_test.lua` 的 I 组、`test_daemon.py` 的 Q 请求）。
- 运行前确认模型可加载，并将路径统一转为绝对路径。模型加载失败时，整个脚本立即中止；核心返回 `no_model` 时，各测试的等待循环也立即退出，避免每项测试都等待 60 秒。
- 空闲卸载类测试的时限用 1 秒；守护进程测试在主机上把监督进程的心跳调为 0.5 秒（`--heartbeat-ms 500 --max-backoff-s 1`），冻结检测由约 22 秒缩短到约 3 秒。

## 清单

| 文件 | 内容 | 需要模型 |
|---|---|---|
| `smoke_test.lua` | 接口存在、缺模型时优雅降级 | 否 |
| `student_test.lua`、`model_test.lua` | 加载、几组同音词的排序、粗略耗时 | 是 |
| `gen_test.lua` | 候选生成：适用范围（全拼不生成）、简拼、混合输入、隔音符、三字词、词频名次、词表外字；束宽足够大时与逐个打分一致 | 是 |
| `filter_test.lua` | 模拟 Rime 环境中的处理器与过滤器端到端测试 | 是 |
| `unified_order_test.lua` | 统一排序加候选生成时的输出顺序：生成出新词或组内有重复文字时，英文与 emoji 候选留在原位（桩核心） | 否 |
| `session_test.lua` | 同一个 Lua 状态里的多个会话与中途换方案：第二个会话也接上熄屏与 AI 开关的通知，换方案后词典目录与过滤器配置跟着换，组件销毁时断开通知（桩核心） | 否 |
| `idle_test.lua` | 空闲卸载、内存归还、透明重载 | 是 |
| `concurrency_test.lua` | 卸载与输入交错、加载中输入、模块重新初始化、配置反复切换、退出 | 是 |
| `pairs_test.lua`、`select_stats_test.lua` | 个人词对、选词统计 | 否 |
| `input_log_test.lua` | 输入事件流：开关、事件内容、失步判断；`tools/input_log.py` 推导的句子、纠错、词对 | 否 |
| `replay_test.lua` | 回放数据的记录内容；`tools/replay.py` 用记录里的参数逐条复现最终首位；`tools/corpus_report.py` 可运行且不输出文字 | 是 |
| `parity/run_cases.lua`、`parity/compare.py`、`parity/data/` | 1293 题留出集逐题打分，与 Suzu-2.4 的基准对照 | 是 |
| `keysim.lua` | 按键仿真：跳过率、等待时间分布；`chaos=` 可做故障注入（配合 `chaos_daemon.sh`） | 是 |
| `test_daemon.py` | 守护进程：协议、认证、非法输入、时限、并发、换词典与附加词库变化后重读；`--chaos` 做 kill -9、SIGSTOP、删除模型的恢复测试。`--host` 在本机运行，不加则经 adb 测手机上的守护进程 | 是 |
| `test_student.py` | 在手机上另起一个守护进程核对排序与延迟（经 adb） | 是 |

## 与旧核心相比改写的检查

`filter_test.lua` 的 I 与 H 两组原先假设运行时用的是 Qwen，已按 Suzu 的行为改写：

- I 组：上文未预解码时，核心在时限内现场解码并重排（Suzu 解码上文只要几毫秒）；时限内算不完则保持原顺序立即返回，结果进缓存，下一次按键用上。耗时改用墙钟计时：`os.clock` 统计的是整个进程的 CPU 时间，会把推理线程也算进来。
- H 组：用户目录里允许有 `suzu.gguf` 与 `suzu.vocab.txt`（这是用户放进来的模型文件，桌面端按这两个文件名启用 Suzu）。

`concurrency_test.lua` 的 race 场景原先依赖旧核心的生命周期读写锁与测试专用环境变量；新核心的模型只由工作线程持有，改为在卸载前后持续打分与预解码，检查每次调用都在时限内返回、跳过带原因、之后能自动恢复。`idle_test.lua` 与 `concurrency_test.lua` 的内存与线程数统计改为同时支持 macOS 与 Linux，内存归还的门槛按 Suzu 的规模改为 20 MB。

`test_daemon.py` 中“未预解码的上文被跳过”改为“未预解码的上文在时限内打分”，并增加时限、缓存、上文不足 2 个字、客户端计数上报与删除模型文件的检查。

## 词表外字的处理

对于含词表外字（编码为 `<unk>`）的候选，核心返回无效分（-1e12），由 Lua 排到同组末尾。`<unk>` 在训练语料中很常见，单个 `<unk>` 的对数概率远高于任何真实词语。若直接采用该分数，生僻字候选（如“𦜳”“蚍蜉”“噼噼”）会被错误地排到首位。

此规则改变了 1293 题留出集中 8 题的首选，Suzu-2.1 的首选准确率由 92.42% 升到 92.73%。逐题对照的基准按此规则生成。

## 留出集对照

`tests/parity/data/cases.tsv` 有 1293 题，每行为编号、占位列、上文和用 `|` 分隔的同音候选。句子取自模型仓库 Suzu 的 `tests/holdout` 与 `tests/domain`（自编，不含个人数据），候选由雾凇拼音的词典按读音列出。`res_suzu-2.4.tsv` 是 Suzu-2.4（Q8_0）逐题的排序与分数。

`run_all.sh` 先核对模型文件的 sha256 前缀，是 Suzu-2.4 才跑这一项，否则跳过；通过的条件是首选一致率不低于 99.9%。它检查的是核心改动前后打分不变，不是准确率。同一份权重在 macOS 上重复运行、换线程数，结果逐题相同。

换模型版本时重新生成基准，并修改 `run_all.sh` 里的 `PARITY_BASE` 与 `PARITY_SHA`：

```bash
cd tests && lua parity/run_cases.lua <构建目录> <suzu.gguf> <suzu.vocab.txt> parity/data/cases.tsv parity/data/res_<版本>.tsv 2
```

环境变量 `SUZU_PARITY_DIR` 可指向另一套题目与基准。
