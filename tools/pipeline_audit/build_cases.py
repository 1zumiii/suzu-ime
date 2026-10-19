#!/usr/bin/env python3
"""由留出集生成 probe 的输入用例，并导出词典读音表。
用法：python3 -I build_cases.py <cn_dicts 目录> <cases.tsv> <meta.json> <输出目录>
输出：pinyin.json（词 → 读音列表）、full.tsv（完整拼音）、partial.tsv（简拼、首音节全拼加其余声母、只输首音节）。
cases.tsv 与 meta.json 是留出集的两份文件（每行：编号、序号、上文、候选；meta 里每题有 ans 与 dom）。"""
import glob, json, os, re, sys, collections

dicts, cases_p, meta_p, out = sys.argv[1:5]
os.makedirs(out, exist_ok=True)
py = collections.defaultdict(list)
for f in glob.glob(os.path.join(dicts, "*.dict.yaml")):
    body = False
    for ln in open(f, encoding="utf-8"):
        if ln.startswith("..."): body = True; continue
        if not body or ln.startswith("#"): continue
        p = ln.rstrip("\n").split("\t")
        if len(p) >= 2 and re.fullmatch(r"[a-z' ]+", p[1]): py[p[0]].append(p[1])
json.dump(py, open(os.path.join(out, "pinyin.json"), "w", encoding="utf-8"), ensure_ascii=False)
meta = json.load(open(meta_p, encoding="utf-8"))
full, part = [], []
for ln in open(cases_p, encoding="utf-8"):
    c = ln.rstrip("\n").split("\t")
    i = int(c[0]); ctx = c[2]; ans = meta[i]["ans"]; dom = meta[i]["dom"]
    r = py.get(ans)
    if not r: continue
    syl = r[0].split()
    full.append("\t".join([str(i), dom, ctx, ans, "".join(syl)]))
    forms = {"abbr": "".join(s[0] for s in syl), "first": syl[0]}
    if len(syl) > 1: forms["mix"] = syl[0] + "".join(s[0] for s in syl[1:])
    for k, code in forms.items():
        part.append("\t".join([f"{i}{k}", dom, ctx, ans, code]))
open(os.path.join(out, "full.tsv"), "w", encoding="utf-8").write("\n".join(full) + "\n")
open(os.path.join(out, "partial.tsv"), "w", encoding="utf-8").write("\n".join(part) + "\n")
print(len(full), "完整拼音用例，", len(part), "不完整输入用例")
