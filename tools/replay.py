#!/usr/bin/env python3
"""离线回放：用本机的回放数据（select_replay.jsonl）换一套融合参数重新排序，比较首选命中率。只输出数字，可以直接贴出。

用法：
  python3 -I tools/replay.py <文件或目录>… [--anchor-rate 0.1] [--keep-revised] [--set 名=值 …]

回放数据记录了打分组里每个候选的模型分和各融合项的原始计数，重新排序不需要再跑模型。
排序逻辑与 rime/lua/ai_rerank_filter.lua 一一对应（融合、门控、第二阶段、回填）；
“当时配置”一行用记录里的参数重算，应当与记录的最终首位完全一致，不一致说明本脚本与过滤器已经不同步。

第二阶段拼出的候选无法离线重算：某个配置让第一阶段的首位变了时，这条记录的第二阶段结果未知，计入“第二阶段未知”，
不参与该配置的命中率。
--set 追加一个自定义配置，例如 --set fb=0.5 --set cg=1（可用的名字见 PARAMS）。
"""
import argparse
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_io  # noqa: E402

# 融合参数：fb 会话词频、pg 个人词对、cg 纠错记忆、fp 模糊音扣分、gm/gms 门控（输入 3 个字母以上 / 1～2 个字母）、
# qa Rime quality 的系数（过滤器里没有这一项，回放用来复核词库先验）、s2 第二阶段（1 开 0 关）
PARAMS = ("fb", "pg", "cg", "fp", "gm", "gms", "qa", "s2")
NEG = float("-inf")


def fused(e, p):
    if e.get("wd") or e.get("s") is None:
        return NEG
    t = e["s"] + p["fb"] * math.log1p(e.get("fq", 0))
    t += p["pg"] * math.log1p(e.get("pr", 0)) if p["pg"] > 0 else 0
    t += p["cg"] * math.log1p(e.get("ch", 0)) if p["cg"] > 0 else 0
    if p["fp"] > 0 and e.get("fz"):
        t -= p["fp"]
    if p["qa"] and e.get("q") is not None:
        t += p["qa"] * e["q"]
    return t


def stage1(g, inp, p):
    """返回第一阶段排序后的打分组（与过滤器的融合排序和门控一致）。"""
    big = 1 << 30
    model = sorted(g, key=lambda e: e.get("mr") or big)
    arr = sorted(((fused(e, p), (e.get("mr") or big), e) for e in model), key=lambda x: (-x[0], x[1]))
    order = [x[2] for x in arr]
    first = g[0]          # 组内 Rime 原首位
    t0 = fused(first, p)
    if t0 > NEG and arr:
        need = p["gms"] if len(inp) <= 2 else p["gm"]
        margin = arr[0][0] - t0
        if need > 0 and order[0]["t"] != first["t"] and margin < need:
            remembered = p["cg"] > 0 and any(e.get("ch", 0) >= 1.0 for e in g)
            if not remembered:
                order = sorted(order, key=lambda e: g.index(e))
    return order


def final_top(rec, p, base_w1):
    """返回 (最终首位, 是否已知)。base_w1：当时配置下第一阶段的首位，用来判断第二阶段结果能否沿用。"""
    rp = rec["rp"]
    g = rp.get("g")
    if not g:
        return rec["of"], True          # 没有打分：保持 Rime 原顺序
    order = stage1(g, rec["input"], p)
    w1 = order[0]["t"]
    if rp.get("s2"):
        if p["s2"]:
            if w1 != base_w1:
                return None, False
            return rp["s2"], True
    if rp.get("cat") == 1 or rp.get("gp1") == 1:
        return w1, True
    return rp.get("n1", rec["of"]), True


def base_params(rp):
    c = rp.get("cfg", {})
    return {"fb": c.get("fb", 1.5), "pg": c.get("pg", 1.5), "cg": c.get("cg", 3.0), "fp": c.get("fp", 1.0),
            "gm": c.get("gm", 0), "gms": c.get("gms", 0), "qa": 0, "s2": 1}


CONFIGS = [
    ("当时配置", {}),
    ("只用模型分", {"fb": 0, "pg": 0, "cg": 0, "fp": 0, "gm": 0, "gms": 0, "s2": 0}),
    ("关闭第二阶段", {"s2": 0}),
    ("会话词频 fb=0", {"fb": 0}),
    ("会话词频 fb=0.5", {"fb": 0.5}),
    ("个人词对 pg=0", {"pg": 0}),
    ("纠错记忆 cg=0", {"cg": 0}),
    ("纠错记忆 cg=1", {"cg": 1}),
    ("模糊音扣分 fp=0", {"fp": 0}),
    ("模糊音扣分 fp=2", {"fp": 2}),
    ("模糊音扣分 fp=3", {"fp": 3}),
    ("门控 gm=gms=0", {"gm": 0, "gms": 0}),
    ("门控 gm=gms=0.5", {"gm": 0.5, "gms": 0.5}),
    ("门控 gm=gms=1", {"gm": 1, "gms": 1}),
    ("门控 gm=gms=2", {"gm": 2, "gms": 2}),
    ("Rime quality qa=0.5", {"qa": 0.5}),
    ("Rime quality qa=1", {"qa": 1}),
    ("Rime quality qa=2", {"qa": 2}),
]


def coverage(recs, rate):
    """所选词在不在打分范围内（回答“答案根本不在候选里”的比例）。"""
    w = defaultdict(float)
    for r in recs:
        rp, wt = r["rp"], corpus_io.weight(r["kind"], rate)
        w["total"] += wt
        g = rp.get("g")
        if not g:
            w["未打分（跳过或上文不足）"] += wt
            continue
        if r["chosen"] in {e["t"] for e in g}:
            w["在打分组内"] += wt
            continue
        nat = rp.get("nat", [])
        same = [x for x in nat if x.get("e") == rp.get("be")]
        if r["chosen"] in {x["t"] for x in same}:
            w["在同一组，但超出候选数上限"] += wt
        elif r["chosen"] in {x["t"] for x in nat}:
            w["在其他覆盖长度的组（前缀词或整句）"] += wt
        elif rp.get("s2") and r["chosen"] == rp["s2"]:
            w["第二阶段拼出的候选"] += wt
        else:
            w["不在前 20 个候选内"] += wt
    print("\n## 所选词的位置（按抽样率还原）")
    tot = w.pop("total")
    for k, v in sorted(w.items(), key=lambda kv: -kv[1]):
        print(corpus_io.row([k, f"{v:.0f}", f"{100 * v / tot:.1f}%"], (36, 8, 8)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--anchor-rate", type=float, default=0.1)
    ap.add_argument("--keep-revised", action="store_true")
    ap.add_argument("--set", action="append", default=[], metavar="名=值")
    ap.add_argument("--check", action="store_true", help="只做自检：当时配置必须复现全部记录的最终首位，否则退出码 1")
    a = ap.parse_args()

    recs = [r for r in corpus_io.read_replay(a.paths) if r.get("v") == 1 and r.get("rp")]
    _, revised = corpus_io.read_corpus(a.paths)
    n_all = len(recs)
    if not a.keep_revised:
        recs = [r for r in recs if r["id"] not in revised]
    skipped_ew = [r for r in recs if r["rp"].get("cfg", {}).get("ew", 0) > 0]
    recs = [r for r in recs if r not in skipped_ew]
    if not recs:
        sys.exit("没有读到回放记录")

    # 自检：当时配置必须逐条复现
    base_w1 = {}
    mismatch = 0
    for r in recs:
        p = base_params(r["rp"])
        g = r["rp"].get("g")
        base_w1[r["id"]] = stage1(g, r["input"], p)[0]["t"] if g else None
        top, _ = final_top(r, p, base_w1[r["id"]])
        if top != r["ft"]:
            mismatch += 1
    print(f"回放记录 {n_all} 条，去掉 revise {n_all - len(recs) - len(skipped_ew)} 条，"
          f"不支持的配置（expected_length_weight>0）{len(skipped_ew)} 条；自检不一致 {mismatch} 条")
    if a.check:
        sys.exit(1 if mismatch else 0)

    configs = list(CONFIGS)
    if a.set:
        extra = {}
        for kv in a.set:
            k, _, v = kv.partition("=")
            if k not in PARAMS:
                sys.exit(f"未知参数 {k}，可用：{', '.join(PARAMS)}")
            extra[k] = float(v)
        configs.append(("自定义 " + " ".join(a.set), extra))

    print("\n## 首位命中率（按抽样率还原）")
    print("救回、损失：与“当时配置”相比，该配置下变对、变错的次数。")
    widths = (24, 9, 8, 7, 7, 14)
    print(corpus_io.row(["配置", "命中率", "变化", "救回", "损失", "第二阶段未知"], widths))
    base_hit = {}
    for name, over in configs:
        hit_w = tot_w = gain = loss = unknown = 0.0
        for r in recs:
            p = base_params(r["rp"])
            p.update(over)
            wt = corpus_io.weight(r["kind"], a.anchor_rate)
            top, known = final_top(r, p, base_w1[r["id"]])
            if not known:
                unknown += wt
                continue
            ok = top == r["chosen"]
            tot_w += wt
            hit_w += wt * ok
            if name == "当时配置":
                base_hit[r["id"]] = ok
            elif base_hit.get(r["id"]) is not None and ok != base_hit[r["id"]]:
                gain += wt * ok
                loss += wt * (not ok)
        rate = 100 * hit_w / tot_w if tot_w else 0
        if name == "当时配置":
            base_rate = rate
        print(corpus_io.row([name, f"{rate:.1f}%", f"{rate - base_rate:+.1f}", f"{gain:.0f}", f"{loss:.0f}",
                             f"{unknown:.0f}"], widths))

    coverage(recs, a.anchor_rate)


if __name__ == "__main__":
    main()
