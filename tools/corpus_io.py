"""读取本机的选词语料（select_corpus）与回放数据（select_replay），供 corpus_report.py 与 replay.py 共用。

文件由 rime/lua/ai_rerank_processor.lua 写在 Rime 用户目录里：活动文件超过 2 MB 后改名为
<名字>.part-<时间>.<扩展名>，并尽量用 gzip 压缩。参数可以是文件，也可以是目录（目录下按文件名前缀查找）。
多台设备的语料放在不同目录里一起传入即可，id 里带时间戳与序号，同一设备内唯一。
"""
import glob
import gzip
import json
import os
import unicodedata


def expand(paths, prefix):
    """把文件与目录展开成文件列表；目录下取 <prefix>.* 与 <prefix>.part-*（含 .gz）。"""
    out = []
    for p in paths:
        if os.path.isdir(p):
            out += sorted(glob.glob(os.path.join(p, prefix + ".*")))
        else:
            out.append(p)
    return [p for p in out if not p.endswith((".enabled", ".old"))]


def lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for ln in f:
            ln = ln.rstrip("\n")
            if ln:
                yield ln


CORPUS_COLS = ["id", "ts", "input_len", "ctx", "chosen", "ft", "of", "kind", "dt", "model"]


def read_corpus(paths):
    """返回 (记录列表, 被标记 revise 的 id 集合)。记录为 dict，列名见 CORPUS_COLS。"""
    recs, revised = [], set()
    for p in expand(paths, "select_corpus"):
        if "select_corpus" not in os.path.basename(p):
            continue
        for ln in lines(p):
            f = ln.split("\t")
            if f[0] == "revise" and len(f) >= 2:
                revised.add(f[1])
                continue
            if len(f) < len(CORPUS_COLS):
                continue
            r = dict(zip(CORPUS_COLS, f))
            for k in ("ts", "input_len", "dt"):
                r[k] = int(r[k]) if r[k].lstrip("-").isdigit() else 0
            recs.append(r)
    return recs, revised


def read_replay(paths):
    recs = []
    for p in expand(paths, "select_replay"):
        if "select_replay" not in os.path.basename(p):
            continue
        for ln in lines(p):
            try:
                recs.append(json.loads(ln))
            except ValueError:
                pass   # 写到一半被截断的行
    return recs


def weight(kind, anchor_rate):
    """锚点按抽样率还原：一条锚点代表 1/anchor_rate 次“选了首位”。"""
    return 1.0 / anchor_rate if kind == "anchor" else 1.0


def bucket(n, edges):
    """按边界分档：edges=(0, 2, 6) 得到 "0"、"1-2"、"3-6"、">6"。"""
    lo = 0
    for e in edges:
        if n <= e:
            return str(e) if lo == e else f"{lo}-{e}"
        lo = e + 1
    return f">{edges[-1]}"


def pad(s, width, right=False):
    """按显示宽度补空格（中文占两格），用于终端表格对齐。right=True 时右对齐。"""
    s = str(s)
    w = sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in s)
    fill = " " * max(0, width - w)
    return fill + s if right else s + fill


def row(cells, widths):
    """第一列左对齐，其余右对齐。"""
    return "".join(pad(c, w, right=i > 0) for i, (c, w) in enumerate(zip(cells, widths)))
