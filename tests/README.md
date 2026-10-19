# 测试

测试脚本的用法写在各文件开头的注释里。多数测试需要自行编译的 `rime_llm.so`、Suzu 模型文件和 Lua 5.4 解释器。

## 已知情况

`filter_test.lua` 中的 I 与 H 两组检查假设运行时用的是 Qwen（未预解码时保持原顺序、用户目录里没有模型文件），在学生模式下会失败：

- `I. not prepared yet` 与 `I. skipped call returns fast`：学生模式下未预解码时会现场解码，而不是跳过。
- `H. only expected files in user dir`：用户目录里放了 `suzu.gguf` 与 `suzu.vocab.txt`。

新核心的测试应按 Suzu 的行为重写这两组检查。
