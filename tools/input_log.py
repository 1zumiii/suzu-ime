#!/usr/bin/env python3
"""重放输入事件流（input_log.jsonl），推导句子、纠错、词对与最近使用。默认只输出数字，可以直接贴出。

用法：
  python3 -I tools/input_log.py <文件或目录>… [--idle 30] [--export <目录>]

--export 把推导结果写到指定目录（含输入的文字，只放本机）：
  sentences.tsv   完整句子：结束时间、句子
  corrections.tsv 输入法选错：拼音、上文最后 30 个字、错词、对词
  pairs.tsv       相邻词对：前一个词、后一个词、次数
  words.tsv       词：词、次数、最后使用时间

重放规则（与 ime/2026-10-20-input-event-log-and-unified-ranking.md 第 2.3、2.4 节一致）：
- 每个会话一个栈，元素为“段”：一次上屏的文字。选词上屏的段带拼音，用于纠错推导与词边界。
- del：从栈顶段末尾删一个字；栈空时判为失步，当前句标记为不完整。
- 句子封口：句末标点、cut、start、与上一事件间隔超过 --idle 秒。
- 纠错：一段被整段删除后，紧接着上屏的选词段与它拼音相同而词不同，记为输入法选错；拼音不同记为改了说法。
"""
import argparse
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_io  # noqa: E402

ENDS = "。！？!?…"


def norm_py(s):
    return "".join(ch for ch in (s or "") if ch.isalpha()).lower()


class Seg:
    __slots__ = ("text", "orig", "py", "t")

    def __init__(self, text, py=None, t=0):
        self.text, self.orig, self.py, self.t = text, text, py, t


class Session:
    def __init__(self, out):
        self.out = out
        self.stack = []
        self.pending = []        # 本次上屏之前的选词事件（一次上屏可能由几次选词组成）
        self.deleted = []        # 刚被整段删除的段（按删除顺序：从右到左）
        self.waiting = None      # 等待迟到选词的上屏：(新段, 上文, 被删的段)
        self.incomplete = False
        self.last_t = None

    # ---------- 句子 ----------
    def seal(self, t):
        self.settle()
        text = "".join(s.text for s in self.stack)
        if text:
            self.out.sentence(text, t, not self.incomplete,
                              [s for s in self.stack if s.py is not None and s.text == s.orig])
        self.stack, self.deleted, self.incomplete = [], [], False

    # ---------- 事件 ----------
    def on_cmt(self, ev):
        text = ev.get("w", "")
        sels, self.pending = self.pending, []
        joined = "".join(s.get("w", "") for s in sels)
        if sels and text.startswith(joined):
            new = [Seg(s["w"], s.get("in"), ev["t"]) for s in sels]
            if len(text) > len(joined):
                new.append(Seg(text[len(joined):], None, ev["t"]))   # 最后一段的选词迟到（整段上屏）
        else:
            new = [Seg(text, None, ev["t"])]
        ctx = "".join(s.text for s in self.stack)[-30:]
        deleted, self.deleted = self.deleted, []
        if new[-1].py is None:
            self.waiting = (new, ctx, deleted)   # 整段上屏时选词通知晚于上屏：等它补上拼音再推导纠错
        else:
            self.match_corrections(new, ctx, deleted)
        self.stack += new
        if text and text[-1] in ENDS:
            self.seal(ev["t"])

    def on_late_sel(self, ev):
        """整段上屏的选词（late = 1）：补到刚才那次上屏的最后一段上。"""
        if not self.waiting:
            return
        new, ctx, deleted = self.waiting
        self.waiting = None
        top, w = new[-1], ev.get("w", "")
        if top.py is None and w and top.text.endswith(w):
            if top.text != w:     # 多段选词的最后一段：拆成前缀（未知拼音）与这一段
                head = Seg(top.text[:-len(w)], None, top.t)
                top.text = top.orig = w
                i = self.stack.index(top)
                self.stack.insert(i, head)
                new.insert(len(new) - 1, head)
            top.py = ev.get("in")
        self.match_corrections(new, ctx, deleted)

    def settle(self):
        """迟到的选词没来：按已有信息推导纠错。"""
        if self.waiting:
            new, ctx, deleted = self.waiting
            self.waiting = None
            self.match_corrections(new, ctx, deleted)

    def match_corrections(self, new, ctx, deleted):
        """被删的段（恢复成从左到右）与新上屏的选词段依次比较拼音。"""
        if not deleted:
            return
        for a, b in zip(reversed(deleted), new):
            if a.py is None or b.py is None:
                break
            if norm_py(a.py) == norm_py(b.py):
                if a.orig != b.text:
                    self.out.correction(a.py, ctx, a.orig, b.text)
                else:
                    self.out.count["重打同一个词"] += 1
            else:
                self.out.count["改了说法"] += 1
                break
            ctx = (ctx + b.text)[-30:]

    def on_del(self):
        if not self.stack:
            self.incomplete = True
            self.out.count["删除下溢（失步）"] += 1
            return
        top = self.stack[-1]
        top.text = top.text[:-1]
        if not top.text:
            self.deleted.append(self.stack.pop())

    def feed(self, ev, idle):
        t, k = ev.get("t", 0), ev.get("k")
        self.out.count["事件 " + str(k)] += 1
        late = k == "sel" and ev.get("late") == 1
        if not late:
            self.settle()
        if self.last_t is not None and t - self.last_t > idle and k != "start":
            self.seal(self.last_t)
        self.last_t = t
        if k == "start":
            self.seal(t)
        elif late:
            self.out.sel(ev)
            self.on_late_sel(ev)
        elif k == "sel":
            self.pending.append(ev)
            self.out.sel(ev)
        elif k == "cmt":
            self.on_cmt(ev)
        elif k == "del":
            self.on_del()
        elif k == "cut":
            self.out.count["失步 " + str(ev.get("why"))] += 1
            self.seal(t)


class Out:
    def __init__(self):
        self.count = Counter()
        self.sentences, self.corrections = [], []
        self.pairs, self.words, self.word_t = Counter(), Counter(), {}

    def sentence(self, text, t, complete, segs):
        self.count["句子（完整）" if complete else "句子（不完整）"] += 1
        self.count["句子总字数"] += len(text)
        if complete:
            self.sentences.append((t, text))
        for s in segs:
            self.words[s.text] += 1
            self.word_t[s.text] = max(self.word_t.get(s.text, 0), s.t)
        if complete:
            for a, b in zip(segs, segs[1:]):
                self.pairs[(a.text, b.text)] += 1

    def correction(self, py, ctx, wrong, right):
        self.count["输入法选错"] += 1
        self.corrections.append((py, ctx, wrong, right))

    def sel(self, ev):
        self.count["选词（AI 开）" if ev.get("ai") else "选词（AI 关）"] += 1
        if ev.get("i", 1) > 1:
            self.count["选了非首位"] += 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--idle", type=int, default=30, help="间隔超过这么多秒就封口（默认 30）")
    ap.add_argument("--export", metavar="DIR")
    a = ap.parse_args()

    evs = []
    for p in corpus_io.expand(a.paths, "input_log"):
        if os.path.basename(p).startswith("input_log") and not p.endswith(".parts"):
            evs += [e for e in corpus_io.read_jsonl(p) if e.get("v") == 1]
    if not evs:
        sys.exit("没有读到事件")
    evs.sort(key=lambda e: (e.get("s", ""), e.get("q", 0)))

    out = Out()
    sessions = defaultdict(lambda: Session(out))
    for ev in evs:
        sessions[ev.get("s", "")].feed(ev, a.idle)
    for s in sessions.values():
        s.seal(s.last_t or 0)

    c = out.count
    print(f"事件 {len(evs)} 个，会话 {len(sessions)} 个")
    for k in sorted(c):
        print(corpus_io.row([k, c[k]], (24, 10)))
    n = c["句子（完整）"] + c["句子（不完整）"]
    if n:
        print(corpus_io.row(["完整句子占比", f"{100 * c['句子（完整）'] / n:.1f}%"], (24, 10)))

    if a.export:
        os.makedirs(a.export, exist_ok=True)
        with open(os.path.join(a.export, "sentences.tsv"), "w", encoding="utf-8") as f:
            for t, text in out.sentences:
                f.write(f"{t}\t{text}\n")
        with open(os.path.join(a.export, "corrections.tsv"), "w", encoding="utf-8") as f:
            for row in out.corrections:
                f.write("\t".join(row) + "\n")
        with open(os.path.join(a.export, "pairs.tsv"), "w", encoding="utf-8") as f:
            for (x, y), k in out.pairs.most_common():
                f.write(f"{x}\t{y}\t{k}\n")
        with open(os.path.join(a.export, "words.tsv"), "w", encoding="utf-8") as f:
            for w, k in out.words.most_common():
                f.write(f"{w}\t{k}\t{out.word_t[w]}\n")
        print(f"推导结果已写入 {a.export}（含输入的文字，只放本机）")


if __name__ == "__main__":
    main()
