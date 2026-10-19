#!/usr/bin/env python3
"""汇总 keysim_matrix.sh 的输出：按配置（去掉轮次后缀 -rN）分组，报各轮的中位数与范围（最小～最大）。
用法：python3 -I tools/keysim_summary.py <matrix 输出文件> [--markdown]"""
import re, statistics, sys
from collections import OrderedDict

rows = OrderedDict()


def put(cfg, key, val):
    rows.setdefault(cfg, {}).setdefault(key, []).append(val)


for ln in open(sys.argv[1], encoding="utf-8"):
    m = re.match(r"^(\S+)-r\d+ (.*)$", ln.strip())
    if not m:
        continue
    cfg, rest = m.groups()
    if rest.startswith("eligible="):
        e = int(re.search(r"eligible=(\d+)", rest).group(1))
        s = int(re.search(r"skip=(\d+)", rest).group(1))
        put(cfg, "skip%", 100.0 * s / e if e else 0.0)
        put(cfg, "ineligible", int(re.search(r"ineligible=(\d+)", rest).group(1)))
    elif rest.startswith("score1 ms:"):
        for k in ("p50", "p99", "max"):
            put(cfg, "score_" + k, float(re.search(k + r"=([-\d.]+)", rest).group(1)))
        s2 = re.search(r"stage2 ms: p50=([-\d.]+) p99=([-\d.]+) max=([-\d.]+)", rest)
        put(cfg, "stage2_max", float(s2.group(3)))
    elif rest.startswith("key ms:"):
        for k in ("p99", "max"):
            put(cfg, "key_" + k, float(re.search(k + r"=([-\d.]+)", rest).group(1)))
        put(cfg, "key_over50", int(re.search(r">50ms=(\d+)", rest).group(1)))

cols = ["skip%", "score_p50", "score_p99", "score_max", "stage2_max", "key_p99", "key_max", "key_over50"]


def fmt(v):
    if not v:
        return "-"
    med = statistics.median(v)
    if len(v) == 1:
        return f"{med:.1f}"
    return f"{med:.1f}（{min(v):.1f}～{max(v):.1f}）"


md = "--markdown" in sys.argv
if md:
    print("| 配置 | 轮数 | " + " | ".join(cols) + " |")
    print("|---" * (len(cols) + 2) + "|")
for cfg, d in rows.items():
    n = len(d.get("skip%", []))
    cells = [fmt(d.get(c)) for c in cols]
    if md:
        print(f"| {cfg} | {n} | " + " | ".join(cells) + " |")
    else:
        print(f"{cfg:28s} n={n} " + "  ".join(f"{c}={x}" for c, x in zip(cols, cells)))
