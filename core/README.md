# core：新推理核心

状态：待实现。本目录是重构后推理核心的落点，设计要求见 `docs/design/`。

## 必须保持的接口

Lua 侧通过 `require("rime_llm")` 得到一个模块，现有的 `rime/lua/` 与 `tests/` 依赖以下行为，新核心须保持兼容（也可以扩展，但不能破坏）：

- 属性读写：`model_path`、`student_vocab`、`student_ctx`、`n_threads`、`log_dir`、`max_ctx`、`n_seq_max`、`idle_unload_sec` 等。
- `score(上文, 候选表, 是否允许现场解码)`：返回按优劣排序的候选，或在被跳过时返回空值加原因。
- `get_scores()`：返回候选文字到得分的映射，得分为各字对数概率之和。
- `prepare(上文)`、`is_ready()`、`status()`。
- 守护进程模式下，客户端库提供同名接口，另有 `set_enabled`、`screen_off`、`touch`、`connected` 等。

详细行为以 `tests/` 中的测试为准。
