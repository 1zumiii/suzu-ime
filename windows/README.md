# Windows notes (verified 2026-10-19 on Windows 11, MSVC 14.44, branch windows-verify)

- Build: `windows\build_core.bat <llama.cpp b11632 dir> <Lua src dir>` -> `core\build\rime_llm.dll` (4.5 MB), `suzu_bench.exe`, `lua54.exe`.
  Lua 5.4.7 sources work (the doc says 5.4.6). `tests\windows\lua.c` is a minimal interpreter used when the Lua tarball is not at hand.
- Tests: set `LUA_INIT=@<repo>/tests/windows/prelude.lua`, copy `rime_llm.dll` to `rime_llm.so` in the build dir (the tests look for `?.so`), run `bash tests/run_all.sh core/build <suzu.gguf> <suzu.vocab.txt>` from Git Bash.
  The prelude routes the POSIX shell commands of the tests to Git Bash and replaces the `/proc` and `footprint` probes with native `os.rss_mb()` and `os.nthreads()` of the test interpreter.
- Deploy: `windows\deploy_core.ps1 -Dll <rime_llm.dll>` (no installer, no dialog; backs up the old DLL, asks WeaselServer to quit with `/q`, copies, restarts it through an interactive scheduled task, rolls back on failure).
  Rollback: `windows\deploy_core.ps1 -Rollback [-BackupDir <dir>]`.
