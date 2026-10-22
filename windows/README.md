# Windows notes (verified 2026-10-19 on Windows 11, MSVC 14.44, branch windows-verify)

- Build: `windows\build_core.bat <llama.cpp b11632 dir> <Lua src dir>` -> `core\build\rime_llm.dll` (4.5 MB), `suzu_bench.exe`, `lua54.exe`.
  Lua 5.4.7 sources work (the doc says 5.4.6). `tests\windows\lua.c` is a minimal interpreter used when the Lua tarball is not at hand.
- Tests: set `LUA_INIT=@<repo>/tests/windows/prelude.lua`, copy `rime_llm.dll` to `rime_llm.so` in the build dir (the tests look for `?.so`), run `bash tests/run_all.sh core/build <suzu.gguf> <suzu.vocab.txt>` from Git Bash.
  The prelude routes the POSIX shell commands of the tests to Git Bash and replaces the `/proc` and `footprint` probes with native `os.rss_mb()` and `os.nthreads()` of the test interpreter.
- Deploy: `windows\deploy_core.ps1 -Dll <rime_llm.dll>` (no installer, no dialog; backs up the old DLL, asks WeaselServer to quit with `/q`, copies, restarts it through an interactive scheduled task, rolls back on failure).
  Rollback: `windows\deploy_core.ps1 -Rollback [-BackupDir <dir>]`.

## 切换到共用的 ai_rerank_* Lua（与 Mac、手机相同）

`windows/deploy_airerank.ps1` 把小狼毫从“原项目加补丁的 llm_processor/llm_filter”切到共用的 `ai_rerank_*`，无引导、有备份、一条命令回滚：

- 切换：`powershell -NoProfile -ExecutionPolicy Bypass -File windows\deploy_airerank.ps1 [-Ranker legacy|unified]`；已切换过的机器改挂共用的光标上文接入层：加 `-SwitchGlue`；只改排序模式：加 `-SetRanker`；回滚：加 `-Rollback [-BackupDir <目录>]`。
- 与 Mac、手机共用的 `rime/lua/ai_rerank_app_ctx.lua` 把 UIA/COM 光标前文接到 `_G.ai_rerank_context_get`（读不到退回上屏历史），并处理点击、切换前台应用、编辑键、上屏后重读快照，以及按实际上文预解码；测试：`tests/windows/win_ctx_test.lua`。
- 无人值守重新部署：先删 `build\rime_ice.schema.yaml` 与 `build\default.yaml`，服务端保持运行，再跑 `WeaselDeployer.exe /deploy`，等编译结果出现后关掉它弹出的窗口（不需要点）。不删 build 文件时 Rime 认为没有变化，什么都不编；服务端没运行时跑 deployer 也不编。
