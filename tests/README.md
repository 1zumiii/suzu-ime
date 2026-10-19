# 测试

全部测试都能在 Linux 或 macOS 主机上运行，不需要真机：

```bash
tests/run_all.sh <core 的构建目录> <suzu.gguf> <suzu.vocab.txt> [--quick]
```

构建目录须含 `rime_llm.so`；含 `rimellmd` 时一并测试守护进程（守护进程只在 Linux 上构建）。Lua 解释器优先用构建目录里的 `lua54`（`-DSUZU_BUILD_TOOLS=ON`）。构建方法见 `core/README.md`。

在 4 核的 Linux 主机上依次运行全部测试约 50 秒（以前约 2 分钟）；6 核以上并行时更短。为此做了这几件事：

- Lua 测试、留出集对照、守护进程测试三组互不依赖，在 6 核及以上的机器上默认并行，核数更少时依次运行；`--parallel`、`--serial` 可强制指定。Lua 测试组里有计时检查，组内始终依次运行。
- 功能检查（排序是否正确、两阶段是否运行）用宽松的时限，机器忙时不会因为超时而误报；时限本身由专门的检查验证（`filter_test.lua` 的 I 组、`test_daemon.py` 的 Q 请求）。
- 开跑前先确认模型能加载，路径一律转为绝对路径。模型加载失败时整个脚本立即中止，各测试的等待循环也在核心返回 `no_model` 时立即退出，不会每个测试都空等 60 秒。
- 空闲卸载类测试的时限用 1 秒；守护进程测试在主机上把监督进程的心跳调为 0.5 秒（`--heartbeat-ms 500 --max-backoff-s 1`），冻结检测由约 22 秒缩短到约 3 秒。

## 清单

| 文件 | 内容 | 需要模型 |
|---|---|---|
| `smoke_test.lua` | 接口存在、缺模型时优雅降级 | 否 |
| `student_test.lua`、`model_test.lua` | 加载、几组同音词的排序、粗略耗时 | 是 |
| `filter_test.lua` | 模拟 Rime 环境中的处理器与过滤器端到端测试 | 是 |
| `idle_test.lua` | 空闲卸载、内存归还、透明重载 | 是 |
| `concurrency_test.lua` | 卸载与输入交错、加载中输入、模块重新初始化、配置反复切换、退出 | 是 |
| `pairs_test.lua`、`select_stats_test.lua` | 个人词对、选词统计 | 否 |
| `input_log_test.lua` | 输入事件流：开关、事件内容、失步判断；`tools/input_log.py` 推导的句子、纠错、词对 | 否 |
| `replay_test.lua` | 回放数据的记录内容；`tools/replay.py` 用记录里的参数逐条复现最终首位；`tools/corpus_report.py` 可运行且不输出文字 | 是 |
| `parity/run_cases.lua`、`parity/compare.py` | 1293 题留出集逐题打分与对照 | 是 |
| `keysim.lua` | 按键仿真：跳过率、等待时间分布；`chaos=` 可做故障注入（配合 `chaos_daemon.sh`） | 是 |
| `test_daemon.py` | 守护进程：协议、认证、非法输入、时限、并发；`--chaos` 做 kill -9、SIGSTOP、删除模型的恢复测试。`--host` 在本机运行，不加则经 adb 测手机上的守护进程 | 是 |
| `test_student.py` | 在手机上另起一个守护进程核对排序与延迟（经 adb） | 是 |

## 与旧核心相比改写的检查

`filter_test.lua` 的 I 与 H 两组原先假设运行时用的是 Qwen，已按 Suzu 的行为改写：

- I 组：上文未预解码时，核心在时限内现场解码并重排（Suzu 解码上文只要几毫秒）；时限内算不完则保持原顺序立即返回，结果进缓存，下一次按键用上。耗时改用墙钟计时：`os.clock` 统计的是整个进程的 CPU 时间，会把推理线程也算进来。
- H 组：用户目录里允许有 `suzu.gguf` 与 `suzu.vocab.txt`（这是用户放进来的模型文件，桌面端按这两个文件名启用 Suzu）。

`concurrency_test.lua` 的 race 场景原先依赖旧核心的生命周期读写锁与测试专用环境变量；新核心的模型只由工作线程持有，改为在卸载前后持续打分与预解码，检查每次调用都在时限内返回、跳过带原因、之后能自动恢复。`idle_test.lua` 与 `concurrency_test.lua` 的内存与线程数统计改为同时支持 macOS 与 Linux，内存归还的门槛按 Suzu 的规模改为 20 MB。

`test_daemon.py` 中“未预解码的上文被跳过”改为“未预解码的上文在时限内打分”，并增加时限、缓存、上文不足 2 个字、客户端计数上报与删除模型文件的检查。

## 词表外字的处理

含词表外字（编成 `<unk>`）的候选，核心返回无效分（-1e12），由 Lua 排到同组末尾。原因：`<unk>` 在训练语料里很常见，单个 `<unk>` 的对数概率远高于任何真实的词，生僻字候选（如“𦜳”“蚍蜉”“噼噼”）因此被排到首位，在输入法里表现为怪词。

此规则改变了 1293 题留出集中 8 题的首选，首选准确率由 92.42% 升到 92.73%。逐题对照的基准文件 `res_q8.tsv` 已按此规则重新生成，旧基准另存为 `res_q8.pre-oov.tsv`。
