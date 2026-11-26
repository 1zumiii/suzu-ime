#!/usr/bin/env python3
"""为 dicts.json 里的词库包生成 weight_map：基准尺度（白霜）的权重换到该包的权重。

各词库包的权重尺度不同，而且不是整体差一个倍数（白霜用自己的语料重新统计过词频）。
附加词库的权重统一按白霜写，App 启用时按当前包的 weight_map 换算。

做法：取两个包都有的多字词（同词同音），按白霜权重以 1/8 个数量级分档，
每档取白霜权重的中位数与目标包权重的中位数作为一个节点，目标包权重取累计最大值保证单调，
少于 30 个词的档丢掉，平台中间重复的点去掉。

只读主词典 import_tables 实际导入的表，跳过生僻字大表（与 rime-suzu 的 dict_pack.py 一致）。

用法：
    python3 tools/dict_weight_map.py <白霜包目录> <白霜主词典> <目标包目录> <目标主词典>
例：
    D=~/.cache/suzu-dict-packs
    python3 tools/dict_weight_map.py $D/frost-c34bbd820db0 rime_frost.dict.yaml $D/ice-da1fbe602e38 rime_ice.dict.yaml
输出一行 JSON，填进该包的 "weight_map"。
"""
import json
import math
import os
import re
import statistics
import sys

BIG_TABLES = {"41448", "GB18030-2022"}
MIN_BIN = 30


def imported_tables(main_path):
    tables, inside = [], False
    with open(main_path, encoding="utf-8") as f:
        for line in f:
            if re.match(r"^import_tables:\s*$", line):
                inside = True
                continue
            if inside:
                m = re.match(r"^\s+-\s+([^\s#]+)", line)
                if m:
                    tables.append(m.group(1))
                elif line.strip() and not line.lstrip().startswith("#"):
                    break
    return [t for t in tables if os.path.basename(t) not in BIG_TABLES]


def load(root, main_dict):
    weights = {}
    for table in imported_tables(os.path.join(root, main_dict)):
        path = os.path.join(root, table + ".dict.yaml")
        if not os.path.isfile(path):
            continue
        body = False
        with open(path, encoding="utf-8") as f:
            for line in f:
                if not body:
                    body = line.startswith("...")
                    continue
                if line.startswith("#"):
                    continue
                cols = line.rstrip("\n").split("\t")
                if len(cols) < 3 or len(cols[0]) < 2:
                    continue
                try:
                    w = float(cols[2])
                except ValueError:
                    continue
                key = (cols[0], cols[1])
                # Rime 对重复的多字词取权重高的那条
                weights[key] = max(w, weights.get(key, 0.0))
    return weights


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    ref = load(sys.argv[1], sys.argv[2])
    target = load(sys.argv[3], sys.argv[4])
    bins = {}
    for key, a in ref.items():
        b = target.get(key)
        if b is None:
            continue
        slot = 0 if a <= 0 else round(math.log10(a) * 8) / 8
        bins.setdefault(slot, []).append((a, b))
    knots, run = [], 0.0
    for slot in sorted(bins):
        pairs = bins[slot]
        if len(pairs) < MIN_BIN:
            continue
        x = statistics.median(a for a, _ in pairs)
        run = max(run, statistics.median(b for _, b in pairs))
        if knots and x <= knots[-1][0]:
            continue
        knots.append([int(x) if x == int(x) else round(x, 3), int(round(run))])
    out = []
    for i, (x, y) in enumerate(knots):
        if out and i + 1 < len(knots) and out[-1][1] == y and knots[i + 1][1] == y:
            continue
        out.append([x, y])
    shared = sum(len(v) for v in bins.values())
    print(f"共有多字词 {shared} 条，{len(out)} 个节点", file=sys.stderr)
    print(json.dumps(out, separators=(",", ":")))


if __name__ == "__main__":
    main()
