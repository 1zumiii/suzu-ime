#!/usr/bin/env python3
"""汇总本机的选词语料（select_corpus.tsv），判断首选错误出在哪一环。只输出数字，不输出任何文字内容，可以直接贴出。

用法：
  python3 -I tools/corpus_report.py <文件或目录>… [--anchor-rate 0.1] [--keep-revised]
  python3 -I tools/corpus_report.py <文件或目录>… --rescore <构建目录> <suzu.gguf> <suzu.vocab.txt>

难例（所选词不是最终首位）分四类：
  H1 所选词就是 Rime 原首位，重排把它挪走了（帮倒忙）
  H2 最终首位等于原首位，都不是所选词（重排没有改变，或没有触发）
  H3 三者都不同（重排改了，但改错了）
  过期 统计信息与这次选词对不上，无法分类
锚点（选了首位，按抽样率还原）中，最终首位不等于原首位的记为救回。
--rescore：只用模型给难例里的所选词、最终首位、原首位打分（只比较字数相同的词），判断错误来自模型还是规则。
语料只存了上文的最后 6 个字（实际使用最多 30 个字），这一步的结论偏保守。
"""
import argparse
import os
import subprocess
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_io  # noqa: E402


def classify(r):
    c, ft, of = r["chosen"], r["ft"], r["of"]
    if r["kind"] == "anchor":
        return "rescue" if ft != of else "same"
    if c == ft:
        return "stale"
    if c == of:
        return "H1"
    if ft == of:
        return "H2"
    return "H3"


class Acc:
    def __init__(self):
        self.w = defaultdict(float)

    def add(self, cls, w):
        self.w[cls] += w
        self.w["total"] += w

    def row(self, name):
        w = self.w
        tot = w["total"]
        if tot == 0:
            return None
        final_ok = w["rescue"] + w["same"]
        orig_ok = w["same"] + w["H1"]
        return corpus_io.row([name, f"{tot:.0f}", f"{100 * orig_ok / tot:.1f}%", f"{100 * final_ok / tot:.1f}%",
                              f"{w['rescue']:.0f}", f"{w['H1']:.0f}", f"{w['H2']:.0f}", f"{w['H3']:.0f}",
                              f"{w['stale']:.0f}"], WIDTHS)


WIDTHS = (14, 9, 11, 13, 7, 9, 7, 7, 7)
HEAD = corpus_io.row(["分组", "选词数", "原首位对", "最终首位对", "救回", "帮倒忙", "H2", "H3", "过期"], WIDTHS)


def table(title, recs, key, rate, order=None):
    groups = defaultdict(Acc)
    for r in recs:
        groups[key(r)].add(classify(r), corpus_io.weight(r, rate))
    print(f"\n## {title}")
    print(HEAD)
    for k in (order or sorted(groups)):
        if k in groups:
            row = groups[k].row(str(k))
            if row:
                print(row)


def rescore(recs, build, model, vocab):
    """只用模型重新打分。通过管道把（上文，候选）交给 Lua 辅助脚本，结果只在内存里。"""
    helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), "corpus_rescore.lua")
    lua = os.path.join(build, "lua54")
    if not os.access(lua, os.X_OK):
        lua = os.path.join(build, "lua54.exe")   # Windows
    if not os.access(lua, os.X_OK):
        lua = "lua5.4"
    jobs = []
    for r in recs:
        cls = classify(r)
        if cls not in ("H1", "H2", "H3") or len(r["ctx"]) < 2:
            continue
        a = r["chosen"]
        b = r["of"] if cls == "H2" else r["ft"]
        if len(a) != len(b):
            jobs.append((cls, None))
            continue
        jobs.append((cls, (r["ctx"], a, b)))
    feed = "".join(f"{j[1][0]}\t{j[1][1]}|{j[1][2]}\n" for j in jobs if j[1])
    p = subprocess.run([lua, helper, build, model, vocab], input=feed, capture_output=True,
                       text=True, encoding="utf-8")
    if p.returncode != 0:
        sys.exit("重新打分失败：" + p.stderr.strip()[-300:])
    out = iter(p.stdout.splitlines())
    res = defaultdict(lambda: defaultdict(int))
    for cls, job in jobs:
        if job is None:
            res[cls]["字数不同"] += 1
            continue
        ln = next(out, "")
        if ln == "skip":
            res[cls]["未打分"] += 1
            continue
        sa, sb = (float(x) for x in ln.split("|"))
        res[cls]["模型偏向所选词" if sa > sb else "模型偏向错词"] += 1
    print("\n## 只用模型重新打分（难例，上文至少 2 个字）")
    print("H1、H3：所选词与最终首位比；H2：所选词与原首位比。")
    print("H1、H3 里“模型偏向所选词”的，错误来自融合、门控或第二阶段；H2 里的，是模型本可救回但没有生效。")
    cols = ["模型偏向所选词", "模型偏向错词", "字数不同", "未打分"]
    widths = (8, 16, 14, 10, 8)
    print(corpus_io.row(["类别"] + cols, widths))
    for cls in ("H1", "H2", "H3"):
        print(corpus_io.row([cls] + [res[cls][c] for c in cols], widths))

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--anchor-rate", type=float, default=0.1, help="记录里没有抽样率时用的锚点抽样率（llm_rerank/select_anchor_rate，默认 0.1）")
    ap.add_argument("--keep-revised", action="store_true", help="保留选词后 3 秒内退格的记录（默认去掉，这类所选词可能本身是错的）")
    ap.add_argument("--rescore", nargs=3, metavar=("BUILD", "MODEL", "VOCAB"))
    a = ap.parse_args()

    recs, revised = corpus_io.read_corpus(a.paths)
    n_all = len(recs)
    if not a.keep_revised:
        recs = [r for r in recs if r["id"] not in revised]
    if not recs:
        sys.exit("没有读到语料记录")
    hard = sum(r["kind"] == "hard" for r in recs)
    print(f"记录 {n_all} 条，去掉 revise {n_all - len(recs)} 条；难例 {hard}，锚点 {len(recs) - hard}（抽样率 {a.anchor_rate}）")
    print("“原首位对”与“最终首位对”按锚点抽样率还原后计算；救回、帮倒忙、H2、H3、过期为还原后的次数。")

    table("总体", recs, lambda r: "全部", a.anchor_rate)
    table("按模型", recs, lambda r: r["model"], a.anchor_rate)
    table("按上文长度（字，最多记 6 个）", recs, lambda r: corpus_io.bucket(len(r["ctx"]), (0, 2, 5)),
          a.anchor_rate, ["0", "1-2", "3-5", ">5"])
    table("按距上次上屏（秒）", recs, lambda r: corpus_io.bucket(r["dt"], (5, 20, 60)), a.anchor_rate,
          ["0-5", "6-20", "21-60", ">60"])
    table("按输入字母数", recs, lambda r: corpus_io.bucket(r["input_len"], (2, 6, 10)), a.anchor_rate,
          ["0-2", "3-6", "7-10", ">10"])
    table("按所选词字数", recs, lambda r: corpus_io.bucket(len(r["chosen"]), (1, 2, 3)), a.anchor_rate,
          ["1", "2", "3", ">3"])
    table("最终首位是否为四字以上且不同于原首位（多为第二阶段拼接）", recs,
          lambda r: "是" if len(r["ft"]) >= 4 and r["ft"] != r["of"] else "否", a.anchor_rate)

    if a.rescore:
        rescore(recs, *a.rescore)


if __name__ == "__main__":
    main()
