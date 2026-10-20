#!/usr/bin/env python3
"""从历史文字一次性导入“基础词对表”（personal_pairs_base.tsv），只含哈希，不含任何可读的词。

用法：
  python3 -I tools/import_pair_base.py --dict <cn_dicts 目录> --out <用户目录>/personal_pairs_base.tsv --text <文本文件，每行一句>
  python3 -I tools/import_pair_base.py --dict … --out … --chatgpt <ChatGPT 导出目录> [--before YYYY-MM]
  加 --merge 时保留已有文件的盐并累加次数。

只输出行数与字节数，不输出任何文字。词对的统计口径与运行时一致：相邻两个词（词典最大匹配切分），两个都是单字的不记。
ChatGPT 导出只取用户自己发的消息：主路径、不超过 500 字、无代码块、与上一条回答的 8 字片段重合不超过 50%、汉字占比不低于 30%。
哈希为 64 位 FNV-1a，输入为 盐 + 前词 + "\\x01" + 当前词（UTF-8），须与 rime/lua/personal_pairs.lua 一致。"""
import argparse, collections, datetime, json, os, re, secrets, stat, sys

MASK = (1 << 64) - 1


def fnv(data: bytes) -> int:
    h = 0xcbf29ce484222325
    for b in data:
        h = ((h ^ b) * 0x100000001b3) & MASK
    return h


def pair_hash(salt: str, prev: str, word: str) -> int:
    return fnv((salt + prev + "\x01" + word).encode("utf-8"))


han = lambda c: "一" <= c <= "鿿"


def load_dict(d):
    words = set()
    for name in ("8105", "base", "ext"):
        p = f"{d}/{name}.dict.yaml"
        if not os.path.exists(p):
            continue
        body = False
        for ln in open(p, encoding="utf-8"):
            if ln.startswith("..."):
                body = True
                continue
            if body and ln.strip() and not ln.startswith("#"):
                words.add(ln.split("\t")[0])
    return words


def fmm(seq, words):
    out, i = [], 0
    while i < len(seq):
        for L in (4, 3, 2, 1):
            w = seq[i:i + L]
            if len(w) == L and w in words:
                out.append(w)
                i += L
                break
        else:
            out.append(seq[i])
            i += 1
    return out


def grams(t, k=8):
    t = re.sub(r"\s+", "", t)
    return {t[i:i + k] for i in range(max(0, len(t) - k + 1))}


def sentences_from_chatgpt(d, before):
    convs = json.load(open(d + "/conversations.json", encoding="utf-8"))
    for cv in convs:
        mp, n, path = cv["mapping"], cv.get("current_node"), []
        while n and mp.get(n):
            path.append(mp[n])
            n = mp[n].get("parent")
        prev = set()
        for node in path[::-1]:
            m = node.get("message")
            if not m:
                continue
            t = "\n".join(p for p in (m["content"].get("parts") or []) if isinstance(p, str))
            role = m["author"]["role"]
            if role == "assistant":
                if t:
                    prev = grams(t)
                continue
            if role != "user" or not t.strip() or len(t) > 500 or "```" in t:
                continue
            g = grams(t)
            if g and len(g & prev) / len(g) > 0.5:
                continue
            if sum(1 for c in t if han(c)) / len(t) < 0.3:
                continue
            ct = m.get("create_time")
            if before and (not ct or datetime.datetime.fromtimestamp(ct).strftime("%Y-%m") >= before):
                continue
            yield from (s for s in re.split(r"[。！？!?\n；;]+", t) if s.strip())


def sentences_from_text(p):
    for ln in open(p, encoding="utf-8"):
        if ln.strip():
            yield ln.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--text")
    ap.add_argument("--chatgpt")
    ap.add_argument("--before")
    ap.add_argument("--merge", action="store_true")
    a = ap.parse_args()
    if bool(a.text) == bool(a.chatgpt):
        sys.exit("须指定 --text 或 --chatgpt 之一")
    salt, counts = None, collections.Counter()
    if a.merge and os.path.exists(a.out):
        for ln in open(a.out, encoding="ascii"):
            m = re.match(r"^#salt\t(\w+)", ln)
            if m:
                salt = m.group(1)
            elif "\t" in ln:
                h, c = ln.split("\t")
                counts[int(h, 16)] += int(c)
    if salt is None:
        salt, counts = secrets.token_hex(8), collections.Counter()
    words = load_dict(a.dict)
    src = sentences_from_text(a.text) if a.text else sentences_from_chatgpt(a.chatgpt, a.before)
    n_sent = n_pair = 0
    for s in src:
        n_sent += 1
        for seg in re.findall(r"[一-鿿]+", s):
            ws = fmm(seg, words)
            for x, y in zip(ws, ws[1:]):
                if len(x) == 1 and len(y) == 1:
                    continue
                counts[pair_hash(salt, x, y)] += 1
                n_pair += 1
    tmp = a.out + ".tmp"
    with open(tmp, "w", encoding="ascii") as f:
        f.write(f"#salt\t{salt}\n")
        for h in sorted(counts):
            f.write(f"{h:016x}\t{min(counts[h], 255)}\n")
    os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(tmp, a.out)
    print(f"句子 {n_sent}，词对记录 {n_pair}，不同词对 {len(counts)}，文件 {os.path.getsize(a.out)} 字节")


if __name__ == "__main__":
    main()
