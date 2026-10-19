#!/usr/bin/env python3
"""比较两份逐题打分结果（res_q8.tsv 格式）：首选一致率、完整排序一致率、分数最大差。
用法：python3 -I compare.py <基准.tsv> <对照.tsv> [--meta meta.json]
带 --meta 时另报两份结果各自的首选准确率（首选是否等于标准答案）。"""
import json, sys

def load(p):
    d = {}
    for ln in open(p, encoding="utf-8"):
        f = ln.rstrip("\n").split("\t")
        if len(f) < 3:
            continue
        items = []
        for x in f[2].split("|"):
            w, _, s = x.rpartition(":")
            items.append((w, float(s)))
        d[f[0]] = items
    return d

a, b = load(sys.argv[1]), load(sys.argv[2])
meta = None
if "--meta" in sys.argv:
    meta = json.load(open(sys.argv[sys.argv.index("--meta") + 1], encoding="utf-8"))
common = sorted(set(a) & set(b), key=int)
top = sum(a[k][0][0] == b[k][0][0] for k in common)
full = sum([w for w, _ in a[k]] == [w for w, _ in b[k]] for k in common)
maxd = 0.0
for k in common:
    sa = dict(a[k]); sb = dict(b[k])
    for w in sa:
        if w in sb:
            maxd = max(maxd, abs(sa[w] - sb[w]))
print(f"cases: base={len(a)} other={len(b)} common={len(common)}")
print(f"top-1 agreement: {top}/{len(common)} = {100.0 * top / max(1, len(common)):.2f}%")
print(f"full-order agreement: {full}/{len(common)} = {100.0 * full / max(1, len(common)):.2f}%")
print(f"max |score diff|: {maxd:.4f}")
diff = [k for k in common if a[k][0][0] != b[k][0][0]]
if diff:
    print("top-1 differs at:", " ".join(diff[:20]))
if meta:
    for name, d in (("base", a), ("other", b)):
        hit = sum(d[k][0][0] == meta[int(k)]["ans"] for k in d)
        print(f"accuracy {name}: {hit}/{len(d)} = {100.0 * hit / max(1, len(d)):.2f}%")
