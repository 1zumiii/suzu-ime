#!/bin/bash
# 按键仿真的对照测量：若干个核心构建 × {进程内, 守护进程} × 若干降速倍数，交替重复，同一轮用同一随机种子。
# 用法：tools/keysim_matrix.sh [-n 词数] [-r 轮数] [-s "1 2.5"] [-o 输出文件] <suzu.gguf> <suzu.vocab.txt> 名称=构建目录 …
#   构建目录里须有 rime_llm.so（进程内）、rimellmd 与 client/librime_llm.so（或 client/rime_llm.so）（守护进程）。
#   降速倍数大于 1 时，构建须打开测量钩子（core 的 -DSUZU_TEST_HOOKS=ON；环境变量 SUZU_SLOWDOWN 与 RIME_LLM_SLOWDOWN 都会设置）。
#   输出：每次运行 3 行（跳过率与原因、score 等待分布、每次按键的耗时分布），只含数字。
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
N=100; REPS=3; SLOWS="1 2.5"; OUT=/dev/stdout
while getopts "n:r:s:o:" o; do
  case $o in n) N=$OPTARG ;; r) REPS=$OPTARG ;; s) SLOWS=$OPTARG ;; o) OUT=$OPTARG ;; *) exit 64 ;; esac
done
shift $((OPTIND - 1))
MODEL="$1"; VOCAB="$2"; shift 2
CASES="$REPO/../reference/holdout-parity/cases.tsv"
LUA="${LUA:-lua}"
port=17970

run_daemon() {   # run_daemon <构建目录> <降速> <标签> <种子>
  local B=$1 slow=$2 label=$3 seed=$4 W
  W="$(mktemp -d "${TMPDIR:-/tmp}/keysim_d.XXXX")"
  mkdir -p "$W/rime" "$W/so"
  if [[ -f "$B/client/librime_llm.so" ]]; then ln -s "$B/client/librime_llm.so" "$W/so/rime_llm.so"
  else ln -s "$B/client/rime_llm.so" "$W/so/rime_llm.so"; fi
  port=$((port + 1))
  SUZU_SLOWDOWN=$slow RIME_LLM_SLOWDOWN=$slow "$B/rimellmd" --supervise --model "$MODEL" --student-vocab "$VOCAB" \
    --n-seq-max 10 --rime-dir "$W/rime" --log-dir "$W" --port $port --threads 2 --idle-sec 0 > "$W/daemon.out" 2>&1 &
  local sup=$!
  for _ in $(seq 50); do [[ -f "$W/rime/llm_token" ]] && break; sleep 0.1; done
  KEYSIM_UD="$W/rime" KEYSIM_PORT=$port "$LUA" "$REPO/tests/keysim.lua" "$REPO" "$W/so" "$CASES" "$N" \
    mode=daemon rime_dir="$W/rime" seed="$seed" label="$label" 2>/dev/null | grep -v " status "
  kill $sup; sleep 1; pkill -f "rimellmd.*--port $port " 2>/dev/null
  rm -rf "$W"
}

for rep in $(seq "$REPS"); do
  for slow in $SLOWS; do
    for spec in "$@"; do
      name="${spec%%=*}"; B="$(cd "${spec#*=}" && pwd)"
      SUZU_SLOWDOWN=$slow RIME_LLM_SLOWDOWN=$slow "$LUA" "$REPO/tests/keysim.lua" "$REPO" "$B" "$CASES" "$N" \
        model="$MODEL" vocab="$VOCAB" threads=2 seed="$rep" label="$name-inproc-x$slow-r$rep" 2>/dev/null | grep -v " status " >> "$OUT"
      run_daemon "$B" "$slow" "$name-daemon-x$slow-r$rep" "$rep" >> "$OUT"
    done
  done
done
