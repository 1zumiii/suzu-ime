#!/bin/bash
# 在 Linux / macOS 主机上跑全部测试（不需要真机）。4 核的 Linux 主机上依次运行约 50 秒（以前约 2 分钟）。
# 用法：tests/run_all.sh <构建目录> <suzu.gguf> <suzu.vocab.txt> [--quick] [--serial | --parallel]
#   路径可以是相对路径（相对于当前目录），脚本会先转成绝对路径并检查文件存在。
#   <构建目录>：core/ 的 CMake 构建目录，需要 rime_llm.so；有 rimellmd 时一并测守护进程（只在 Linux 上构建）
#   Lua 解释器：优先用构建目录里的 lua54（-DSUZU_BUILD_TOOLS=ON），否则用 PATH 里的 lua5.4 或 lua
#   --quick：跳过守护进程的破坏性测试
#   --serial / --parallel：三组测试依次或并行运行。Lua 测试、留出集对照、守护进程测试互不依赖，各用各的进程、端口与临时目录；
#   默认在 6 核及以上的机器上并行，核数更少时依次运行（CPU 不够时，计时检查会因为超时而失败）。
#   每个测试后面的括号是它的耗时。Lua 测试组里有计时检查，组内始终依次运行。
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
[[ $# -ge 3 ]] || { sed -n 3,9p "$0"; exit 64; }
abspath() { [[ -e "$1" ]] || { echo "找不到 $1" >&2; exit 2; }; (cd "$(dirname "$1")" && echo "$(pwd -W 2>/dev/null || pwd)/$(basename "$1")"); }   # Git Bash: C:/... so native Windows interpreters can read paths embedded in -e strings
B="$(abspath "$1")"; MODEL="$(abspath "$2")"; VOCAB="$(abspath "$3")"; shift 3
[[ -f "$B/rime_llm.so" ]] || { echo "$B 里没有 rime_llm.so：先按 core/README.md 构建"; exit 2; }
QUICK=0
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)"
SERIAL=$(( CORES < 6 ? 1 : 0 ))
for a in "$@"; do
  case "$a" in --quick) QUICK=1 ;; --serial) SERIAL=1 ;; --parallel) SERIAL=0 ;; *) echo "未知选项 $a"; exit 64 ;; esac
done
LUA="$B/lua54"; [[ -x "$LUA" ]] || LUA="$(command -v lua5.4 || command -v lua)"
[[ -x "$LUA" ]] || { echo "找不到 Lua 5.4 解释器：用 -DSUZU_BUILD_TOOLS=ON 构建 lua54，或把 lua 放进 PATH"; exit 2; }
T="$(mktemp -d "${TMPDIR:-/tmp}/suzu_tests.XXXX")"
START=$SECONDS

# 先确认模型能加载：加载失败时后面每个测试都会失败，没有必要逐个跑
if ! (cd "$REPO/tests" && "$LUA" -e "
  package.cpath = '$B/?.so;' .. package.cpath
  local m = require('rime_llm'); m.log_dir = '$T'; m.student_vocab = '$VOCAB'; m.model_path = '$MODEL'
  local t0 = os.time()
  while true do
    local r, why = m.score('他在公司当', { '经理', '经历' }, true)
    if r then os.exit(0) end
    if why == 'no_model' or os.time() - t0 > 20 then os.exit(1) end
    m.sleep_ms(50)
  end" 2>/dev/null); then
  echo "模型加载失败，测试中止。日志：$(tail -3 "$T/rime_llm_log.txt" 2>/dev/null)"
  rm -rf "$T"; exit 1
fi

# run <名称> <命令…>：只输出结论、耗时与失败的检查；失败时在 $T/failed 里记一行
run() {
  local name="$1"; shift
  local t0=$SECONDS
  local out; out="$("$@" 2>/dev/null)"; local rc=$?
  local dt=$((SECONDS - t0))
  if [[ $rc -eq 0 ]] && ! grep -q "^FAIL" <<<"$out"; then echo "ok    $name (${dt}s)"
  else
    echo "FAIL  $name (exit $rc, ${dt}s)"; grep -E "^FAIL" <<<"$out" | sed 's/^/      /'
    echo "$name" >> "$T/failed"
  fi
}
d() { mkdir -p "$T/$1"; echo "$T/$1"; }   # 每个测试用自己的临时目录（有的测试会清空它）

# 第一组：Lua 测试（含计时检查，依次运行）
lua_tests() {
  # 桌面端的安装布局：用户目录里有 lua/rime_llm.so、suzu.gguf、suzu.vocab.txt
  local UD="$T/ud"; mkdir -p "$UD/lua"; cp "$B/rime_llm.so" "$UD/lua/"; cp "$MODEL" "$UD/suzu.gguf"; cp "$VOCAB" "$UD/suzu.vocab.txt"
  export SUZU_VOCAB="$VOCAB"
  run "smoke_test"            "$LUA" smoke_test.lua "$B" "$(d smoke)"
  run "student_test"          "$LUA" student_test.lua "$B" "$MODEL" "$VOCAB" "$(d student)" 2
  run "gen_test"              "$LUA" gen_test.lua "$B" "$MODEL" "$VOCAB" "$(d gen)"
  run "pairs_test"            "$LUA" pairs_test.lua "$REPO" "$(d pairs)"
  run "select_stats_test"     "$LUA" select_stats_test.lua "$REPO" "$(d select)"
  run "input_log_test"        "$LUA" input_log_test.lua "$REPO" "$(d ilog)"
  run "replay_test"           "$LUA" replay_test.lua "$REPO" "$B" "$MODEL" "$VOCAB" "$(d replay)"
  run "filter_test"           "$LUA" filter_test.lua "$REPO" "$UD" "$UD/suzu.gguf"
  run "idle_test"             "$LUA" idle_test.lua "$B" "$MODEL" "$(d idle)" exit
  local c
  for c in race loading reinit toggles exit_wait exit_window; do
    run "concurrency_test $c" "$LUA" concurrency_test.lua "$B" "$MODEL" "$(d "cc_$c")" "$c"
  done
}

# 第二组：留出集与基准逐题核对（首选一致率不低于 99.9%）
parity() {
  local REF="$REPO/../reference/holdout-parity"
  [[ -f "$REF/cases.tsv" ]] || { echo "skip  holdout parity（找不到 $REF）"; return; }
  # 基准分数是 Suzu-2.1（Q8_0）的；换了别的权重逐题必然有差异，此项只对 2.1 有意义
  local sha; sha="$( (shasum -a 256 "$MODEL" 2>/dev/null || sha256sum "$MODEL" 2>/dev/null) | cut -c1-16)"
  if [[ -n "$sha" && "$sha" != e5c8d551ee35d08b ]]; then echo "skip  holdout parity（基准是 Suzu-2.1 的，当前权重 sha256 前缀 $sha）"; return; fi
  local t0=$SECONDS
  "$LUA" parity/run_cases.lua "$B" "$MODEL" "$VOCAB" "$REF/cases.tsv" "$T/parity.tsv" 2 >/dev/null 2>&1
  local line pct
  line="$(python3 -I parity/compare.py "$REF/res_q8.tsv" "$T/parity.tsv" | grep "top-1 agreement")"
  pct="$(sed -E 's/.*= ([0-9.]+)%.*/\1/' <<<"$line")"
  if python3 -c "import sys; sys.exit(0 if float('${pct:-0}') >= 99.9 else 1)"; then echo "ok    holdout parity ($line, $((SECONDS - t0))s)"
  else echo "FAIL  holdout parity ($line)"; echo parity >> "$T/failed"; fi
}

# 第三组：守护进程（主机构建）
daemon_tests() {
  [[ -x "$B/rimellmd" ]] || { echo "skip  test_daemon.py（构建目录里没有 rimellmd）"; return; }
  local chaos="--chaos"; [[ $QUICK -eq 1 ]] && chaos=""
  run "test_daemon.py --host $chaos" python3 -I test_daemon.py --host "$B/rimellmd" "$MODEL" "$VOCAB" $chaos
}

cd "$REPO/tests"
if [[ $SERIAL -eq 1 ]]; then
  lua_tests; parity; daemon_tests
else
  parity > "$T/out_parity" &
  p1=$!
  daemon_tests > "$T/out_daemon" &
  p2=$!
  lua_tests
  wait $p1 $p2
  cat "$T/out_parity" "$T/out_daemon"
fi

fails=0; [[ -f "$T/failed" ]] && fails=$(wc -l < "$T/failed" | tr -d ' ')
rm -rf "$T"
echo; [[ $fails -eq 0 ]] && echo "ALL OK ($((SECONDS - START))s)" || echo "$fails FAILED ($((SECONDS - START))s)"
exit $((fails > 0))
