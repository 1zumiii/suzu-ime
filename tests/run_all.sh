#!/bin/bash
# 在 Linux / macOS 主机上跑全部测试（不需要真机）。
# 用法：tests/run_all.sh <构建目录> <suzu.gguf> <suzu.vocab.txt> [--quick]
#   <构建目录>：core/ 的 CMake 构建目录，需要 rime_llm.so；有 rimellmd 与 client/librime_llm.so 时一并测守护进程
#   Lua 解释器：优先用构建目录里的 lua54（-DSUZU_BUILD_TOOLS=ON），否则用 PATH 里的 lua
#   --quick：跳过耗时的部分（守护进程的破坏性测试、按键仿真）
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
B="$(cd "$1" && pwd)"; MODEL="$2"; VOCAB="$3"; QUICK="${4:-}"
LUA="$B/lua54"; [[ -x "$LUA" ]] || LUA="$(command -v lua5.4 || command -v lua)"
[[ -x "$LUA" ]] || { echo "找不到 Lua 5.4 解释器：用 -DSUZU_BUILD_TOOLS=ON 构建 lua54，或把 lua 放进 PATH"; exit 2; }
T="$(mktemp -d "${TMPDIR:-/tmp}/suzu_tests.XXXX")"
fails=0
run() {   # run <名称> <命令…>：输出只留失败行与结论
  local name="$1"; shift
  local out; out="$("$@" 2>/dev/null)"; local rc=$?
  if [[ $rc -eq 0 ]] && ! grep -q "^FAIL" <<<"$out"; then echo "ok    $name"
  else echo "FAIL  $name (exit $rc)"; grep -E "^FAIL" <<<"$out" | sed 's/^/      /'; fails=$((fails + 1)); fi
}
# 桌面端的安装布局：用户目录里有 lua/rime_llm.so、suzu.gguf、suzu.vocab.txt
UD="$T/ud"; mkdir -p "$UD/lua"; cp "$B/rime_llm.so" "$UD/lua/"; cp "$MODEL" "$UD/suzu.gguf"; cp "$VOCAB" "$UD/suzu.vocab.txt"
export SUZU_VOCAB="$VOCAB"

cd "$REPO/tests"
# 每个测试用自己的临时目录（有的测试会清空它）
d() { mkdir -p "$T/$1"; echo "$T/$1"; }
run "smoke_test"            "$LUA" smoke_test.lua "$B" "$(d smoke)"
run "student_test"          "$LUA" student_test.lua "$B" "$MODEL" "$VOCAB" "$(d student)" 2
run "pairs_test"            "$LUA" pairs_test.lua "$REPO" "$(d pairs)"
run "select_stats_test"     "$LUA" select_stats_test.lua "$REPO" "$(d select)"
run "filter_test"           "$LUA" filter_test.lua "$REPO" "$UD" "$UD/suzu.gguf"
run "idle_test"             "$LUA" idle_test.lua "$B" "$MODEL" "$(d idle)" exit
for c in race loading reinit toggles exit_wait exit_window; do
  run "concurrency_test $c" "$LUA" concurrency_test.lua "$B" "$MODEL" "$(d "cc_$c")" "$c"
done

# 留出集：与基准逐题核对（首选一致率不低于 99.9%）
REF="$REPO/../reference/holdout-parity"
if [[ -f "$REF/cases.tsv" ]]; then
  "$LUA" parity/run_cases.lua "$B" "$MODEL" "$VOCAB" "$REF/cases.tsv" "$T/parity.tsv" 4 >/dev/null 2>&1
  line="$(python3 -I parity/compare.py "$REF/res_q8.tsv" "$T/parity.tsv" | grep "top-1 agreement")"
  pct="$(sed -E 's/.*= ([0-9.]+)%.*/\1/' <<<"$line")"
  if python3 -c "import sys; sys.exit(0 if float('$pct') >= 99.9 else 1)"; then echo "ok    holdout parity ($line)"
  else echo "FAIL  holdout parity ($line)"; fails=$((fails + 1)); fi
fi

# 守护进程（主机构建）
if [[ -x "$B/rimellmd" ]]; then
  chaos="--chaos"; [[ "$QUICK" == "--quick" ]] && chaos=""
  run "test_daemon.py --host $chaos" python3 -I test_daemon.py --host "$B/rimellmd" "$MODEL" "$VOCAB" $chaos
fi

rm -rf "$T"
echo; [[ $fails -eq 0 ]] && echo "ALL OK" || echo "$fails FAILED"
exit $((fails > 0))
