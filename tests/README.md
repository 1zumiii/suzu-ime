# 测试

全部测试都能在 Linux 或 macOS 主机上运行，不需要真机：

```bash
tests/run_all.sh <core 的构建目录> <suzu.gguf> <suzu.vocab.txt> [--quick]
```

构建目录须含 `rime_llm.so`；含 `rimellmd` 与 `client/librime_llm.so` 时一并测试守护进程。Lua 解释器优先用构建目录里的 `lua54`（`-DSUZU_BUILD_TOOLS=ON`）。构建方法见 `core/README.md`。

## 清单

| 文件 | 内容 | 需要模型 |
|---|---|---|
| `smoke_test.lua` | 接口存在、缺模型时优雅降级 | 否 |
| `student_test.lua`、`model_test.lua` | 加载、几组同音词的排序、粗略耗时 | 是 |
| `filter_test.lua` | 模拟 Rime 环境中的处理器与过滤器端到端测试 | 是 |
| `idle_test.lua` | 空闲卸载、内存归还、透明重载 | 是 |
| `concurrency_test.lua` | 卸载与输入交错、加载中输入、模块重新初始化、配置反复切换、退出 | 是 |
| `pairs_test.lua`、`select_stats_test.lua` | 个人词对、选词统计 | 否 |
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
