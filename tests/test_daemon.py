#!/usr/bin/env python3
"""rimellmd 端到端测试：协议、认证、非法输入、打分正确性、时限、并发；加 --chaos 再做破坏性测试。

两种运行方式：
  手机：python3 -I tests/test_daemon.py [--chaos]
        守护进程已在手机上运行（rimellmctl.sh start 或经 Shizuku），经 adb forward 访问。
  主机：python3 -I tests/test_daemon.py --host <rimellmd> <suzu.gguf> <suzu.vocab.txt> [--chaos]
        在本机临时目录里以「监督进程 + 工作进程」方式起一个守护进程（端口 17950），测完自动结束。

--chaos：kill -9 工作进程、SIGSTOP 冻结工作进程、删除模型文件后重启，验证监督进程自动恢复、令牌更换、
         删掉模型后守护进程照常应答（只是不打分）。
"""
import json, os, shutil, signal, socket, struct, subprocess, sys, tempfile, threading, time

SEP = "\x1f"
fails = 0


def check(name, cond, extra=""):
    global fails
    print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if extra else ""))
    if not cond:
        fails += 1


# ---------------------------------------------------------------- 两种环境

class Phone:
    RIME = "/sdcard/Android/data/com.osfans.trime/files/rime"
    port = 17920

    def __init__(self):
        self.adb = os.environ.get("ADB", "adb")
        subprocess.run([self.adb, "forward", f"tcp:{self.port}", f"tcp:{self.port}"], check=True, capture_output=True)

    def sh(self, cmd):
        return subprocess.run([self.adb, "shell", cmd], capture_output=True, text=True, timeout=30).stdout.replace("\r", "").strip()

    def token(self): return self.sh(f"cat {self.RIME}/llm_token")
    def status_file(self): return json.loads(self.sh(f"cat {self.RIME}/ai_status.json"))
    def now(self): return int(self.sh("date +%s"))
    def pids(self): return self.sh("pidof rimellmd").split()
    def kill(self, pid, sig): self.sh(f"kill -{sig} {pid}")
    def model_path(self): return None   # 手机上不做删除模型的测试
    def stop(self): pass


class Host:
    port = 17950

    def __init__(self, binp, model, vocab):
        self.dir = tempfile.mkdtemp(prefix="rimellmd_test_")
        self.rime = os.path.join(self.dir, "rime")
        os.makedirs(self.rime)
        # 模型复制到临时目录，这样删除模型的测试不会动到原文件
        self.model = os.path.join(self.dir, "suzu.gguf")
        self.vocab = os.path.join(self.dir, "suzu.vocab.txt")
        shutil.copy(model, self.model)
        shutil.copy(vocab, self.vocab)
        # 候选生成用的小词典（格式同雾凇 cn_dicts）：--rime-dir 下的 cn_dicts
        os.makedirs(os.path.join(self.rime, "cn_dicts"))
        with open(os.path.join(self.rime, "cn_dicts", "base.dict.yaml"), "w", encoding="utf-8") as f:
            f.write("---\nname: base\n...\n经理\tjing li\t100\n精力\tjing li\t90\n教练\tjiao lian\t50\n家里\tjia li\t40\n")
        self.proc = subprocess.Popen(
            [binp, "--supervise", "--model", self.model, "--student-vocab", self.vocab, "--rime-dir", self.rime,
             "--log-dir", self.dir, "--port", str(self.port), "--threads", "2", "--idle-sec", "0", "--n-seq-max", "10", "--gen-beam", "8",
             # 监督进程的节奏调短：心跳 0.5 秒、重启退避不超过 1 秒（手机上用默认的 3 秒与 30 秒）
             "--heartbeat-ms", "500", "--max-backoff-s", "1",
             # 版本 1 请求（S）的时限放宽：功能检查不因机器忙而超时；时限本身用 Q 请求单独检查
             "--budget-ms", "500", "--sync-budget-ms", "500"],
            stdout=open(os.path.join(self.dir, "daemon.out"), "w"), stderr=subprocess.STDOUT)
        for _ in range(100):
            if os.path.exists(os.path.join(self.rime, "llm_token")):
                break
            time.sleep(0.1)

    def token(self): return open(os.path.join(self.rime, "llm_token")).read().strip()
    def status_file(self): return json.load(open(os.path.join(self.rime, "ai_status.json")))
    def now(self): return int(time.time())

    def pids(self):
        out = subprocess.run(["pgrep", "-f", f"rimellmd.*--port {self.port}"], capture_output=True, text=True).stdout.split()
        return out

    def kill(self, pid, sig): os.kill(int(pid), getattr(signal, "SIG" + sig))
    def model_path(self): return self.model

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(5)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.dir, ignore_errors=True)


# ---------------------------------------------------------------- 协议

def frame(fields):
    b = SEP.join(fields).encode()
    return struct.pack(">I", len(b)) + b


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        c = s.recv(n - len(buf))
        if not c:
            raise EOFError
        buf += c
    return buf


def rpc(s, fields):
    s.sendall(frame(fields))
    n = struct.unpack(">I", recv_exact(s, 4))[0]
    return recv_exact(s, n).decode().split(SEP)


def timed_rpc(s, fields):
    t0 = time.time()
    r = rpc(s, fields)
    return r, (time.time() - t0) * 1000


if "--host" in sys.argv:
    i = sys.argv.index("--host")
    env = Host(*sys.argv[i + 1:i + 4])
else:
    env = Phone()
PORT = env.port


def connect(token=None, auth=True):
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    if auth:
        r = rpc(s, ["H", token or env.token()])
        assert r[0] == "OK", r
    return s


def status():
    s = connect()
    try:
        return json.loads(rpc(s, ["G"])[0])
    finally:
        s.close()


def wait_ready(s, limit=30):
    t0 = time.time()
    rpc(s, ["C", "enabled", "1"])          # 守护进程默认关闭（与手机上方案开关的默认值一致）
    while time.time() - t0 < limit:
        if rpc(s, ["P", "他在公司当"])[0] == "1" and rpc(s, ["I"])[0] == "1":
            return time.time() - t0
        time.sleep(0.1)
    return None


try:
    check("daemon processes running (supervisor + worker)", len(env.pids()) == 2, str(env.pids()))

    # ---- 认证与非法输入
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    check("heartbeat works without auth", rpc(s, ["B"]) == ["PONG"])
    s.close()
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    s.sendall(frame(["S", "0", "他在公司当", "经理", "经历"]))
    try:
        s.settimeout(3); r = s.recv(10)
    except Exception:
        r = b""
    check("unauthenticated request is dropped", r == b"")
    s.close()
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    r = rpc(s, ["H", "0" * 32])
    check("wrong token rejected", r[0] == "ERR", str(r))
    s.close()
    s = connect()
    s.sendall(struct.pack(">I", 10 * 1024 * 1024) + b"x" * 100)
    try:
        s.settimeout(3); r = s.recv(10)
    except Exception:
        r = b""
    check("oversize frame -> connection closed", r == b"")
    s.close()
    s = connect()
    s.sendall(b"\xff\xff")
    time.sleep(0.2); s.close()
    check("daemon survives garbage", status()["errors"] >= 0)
    s = connect()
    check("unknown opcode handled", rpc(s, ["Q"])[0] in ("ERR", "N"))
    check("unknown opcode X handled", rpc(s, ["X"])[0] == "ERR")

    # ---- 关闭状态
    rpc(s, ["C", "enabled", "0"])
    r = rpc(s, ["S", "0", "他在公司当", "经理", "经历"])
    check("disabled -> scoring refused with reason off", r[:2] == ["N", "off"], str(r))

    # ---- 推理
    t = wait_ready(s)
    check("model loads and context prepares", t is not None, f"{t:.1f}s" if t else "")
    time.sleep(0.3)
    # ---- 候选生成（W，版本 3）：简拼生成；全拼不适用。手机上没有开 --gen-beam 时为 no_lexicon
    r = None
    for _ in range(50):
        r = rpc(s, ["W", "500", "3", "他在公司当", "jl"])
        if r[:2] != ["N", "no_lexicon"] or env.__class__.__name__ != "Host":
            break
        time.sleep(0.1)
    if env.__class__.__name__ == "Host":
        check("generate: abbreviation -> words with score and rank", r[0] == "R" and len(r) >= 4 and (len(r) - 1) % 3 == 0
              and set(r[1::3]) <= {"经理", "精力", "教练", "家里"}, str(r))
        r = rpc(s, ["W", "500", "3", "他在公司当", "jingli"])
        check("generate: full pinyin -> not_applicable", r[:2] == ["N", "not_applicable"], str(r))
        # 换词典（X）：rime 目录下的另一个子目录；只重读词典索引，模型不重载；名字不合法或目录没有 base.dict.yaml 时拒绝
        os.makedirs(os.path.join(env.rime, "cn_dicts_x"))
        with open(os.path.join(env.rime, "cn_dicts_x", "base.dict.yaml"), "w", encoding="utf-8") as f:
            f.write("---\nname: base\n...\n敬礼\tjing li\t50\n")
        check("lexicon: bad names rejected", rpc(s, ["X", "../cn_dicts"]) == ["ERR"] and rpc(s, ["X", "a/b"]) == ["ERR"]
              and rpc(s, ["X", "no_such_dir"]) == ["ERR"] and rpc(s, ["X", ""]) == ["ERR"])
        check("lexicon: switch accepted", rpc(s, ["X", "cn_dicts_x"]) == ["OK"])
        r = None
        for _ in range(100):
            r = rpc(s, ["W", "500", "3", "他在公司当", "jl"])
            if r[0] == "R":
                break
            time.sleep(0.1)
        check("lexicon: generate uses the new dictionary", r[0] == "R" and set(r[1::3]) == {"敬礼"}, str(r))
        check("lexicon: switch back", rpc(s, ["X", "cn_dicts"]) == ["OK"])
        for _ in range(100):
            r = rpc(s, ["W", "500", "3", "他在公司当", "jl"])
            if r[0] == "R" and len(r) > 4:
                break
            time.sleep(0.1)
        check("lexicon: original dictionary back", r[0] == "R" and "经理" in r[1::3], str(r))
    else:
        check("generate: answered (R or N)", r[0] in ("R", "N"), str(r))
    cands = ["经理", "经历", "精力", "敬礼", "警力"]
    r = rpc(s, ["S", "0", "他在公司当"] + cands)
    check("score returns a ranking", r[0] == "R" and len(r) == 1 + 2 * len(cands), str(r[:5]))
    check("ranking is sensible (经理 first)", r[0] == "R" and r[1] == "经理", " > ".join(r[1::2]))
    r = rpc(s, ["Q", "500", "我最近没有太多"] + cands)
    check("version-2 request Q works", r[0] == "R" and len(r) == 1 + 2 * len(cands) and set(r[1::2]) == set(cands), " > ".join(r[1::2]))   # 名次随权重而变，只查应答的形状
    lat = []
    for i in range(30):
        r, dt = timed_rpc(s, ["S", "0", "他在公司当"] + cands[i % 3:] + cands[:i % 3])
        lat.append(dt)
    lat.sort()
    print(f"     round-trip latency (prepared context): p50={lat[15]:.1f} ms max={lat[-1]:.1f} ms")
    # Suzu：上文没预解码时在时限内现场解码，而不是跳过（旧核心为 Qwen 设计的“未预解码即跳过”不再适用）
    r, dt = timed_rpc(s, ["Q", "45", "一个没有预解码过的上文"] + cands)
    check("unprepared context is scored within the budget", r[0] == "R" and dt < 45 + 30, f"{r[0]} {dt:.0f} ms")
    r, dt = timed_rpc(s, ["Q", "1", "又一个没有预解码过的很长很长的上文内容"] + cands)
    check("budget is enforced: 1 ms budget -> timeout answered fast", r[:2] == ["N", "timeout"] and dt < 30, f"{r} {dt:.0f} ms")
    time.sleep(0.2)
    r = rpc(s, ["Q", "1", "又一个没有预解码过的很长很长的上文内容"] + cands)
    check("timed-out job finished in background and is served from cache", r[0] == "R", str(r[:2]))
    r = rpc(s, ["S", "1", "他在公司当"] + ["经理"])
    check("single candidate -> no ranking", r[0] == "N")
    r = rpc(s, ["S", "0", "他"] + cands)
    check("one-char context -> not scored, reason no_context", r[:2] == ["N", "no_context"], str(r))
    check("config accepted", rpc(s, ["C", "idle_unload_sec", "1200"]) == ["OK"])
    check("screen-off hint accepted", rpc(s, ["Z", "1"]) == ["OK"])
    st = status()
    check("status reflects screen off (idle limit unchanged when screen_off_sec=0)", st["screen"] == "off" and st["idle_unload_sec"] == 1200, f'{st["screen"]} {st["idle_unload_sec"]}')
    rpc(s, ["Z", "0"])
    st = status()
    check("status reflects screen on", st["screen"] == "on" and st["idle_unload_sec"] == 1200)
    check("status: no_context is not counted as skipped", st["no_context"] >= 1 and st["skipped"] == st["engine"]["skipped"], f'{st["no_context"]} {st["skipped"]}')
    check("client counters accepted", rpc(s, ["K", "10", "9", "1", "0", "0", "30", "0", "0"]) == ["OK"] and status()["client"]["score_calls"] == 10)
    print("     status:", json.dumps(status(), ensure_ascii=False)[:400], "…")
    rpc(s, ["C", "enabled", "1"])          # 切换开关后的 15 秒内，状态文件每 0.3 秒刷新一次
    time.sleep(0.8)
    check("status file is fresh", abs(env.now() - env.status_file()["ts"]) < 3)
    s.close()

    # ---- 并发：多个连接同时打分
    res = []
    def worker(k):
        c = connect(); ok = 0
        for j in range(20):
            r = rpc(c, ["S", "0", "他在公司当" + "的" * (j % 3)] + cands[k % 5:] + cands[:k % 5])
            ok += r[0] in ("R", "N")
        res.append(ok); c.close()
    th = [threading.Thread(target=worker, args=(k,)) for k in range(4)]
    [t.start() for t in th]; [t.join() for t in th]
    check("4 concurrent clients x 20 requests all answered", res == [20] * 4, str(res))
    time.sleep(0.3)
    check("no connection leak", status()["clients"] <= 1)

    if "--chaos" in sys.argv:
        print("--- chaos")
        before = status()
        worker_pid = str(before["pid"])
        old_token = env.token()
        env.kill(worker_pid, "KILL")
        t0 = time.time(); st = None
        while time.time() - t0 < 30:
            try:
                st = status(); break
            except Exception:
                time.sleep(0.3)
        check("worker killed with SIGKILL -> supervisor restarts it", st is not None and str(st["pid"]) != worker_pid, f"{time.time()-t0:.1f}s")
        check("restart is recorded with the reason", st and st["restarts"] == before["restarts"] + 1 and "SIGKILL" in st["last_exit"], st and st["last_exit"])
        check("new token issued on restart", env.token() != old_token)
        s = connect(); check("new worker serves requests", wait_ready(s) is not None); s.close()

        worker_pid = str(status()["pid"]); n_before = status()["restarts"]
        env.kill(worker_pid, "STOP")
        t0 = time.time(); st = None
        while time.time() - t0 < 45:
            time.sleep(0.3)
            try:
                c = socket.create_connection(("127.0.0.1", PORT), timeout=2)
                c.settimeout(2)
                try:
                    if rpc(c, ["B"]) == ["PONG"] and str(status()["pid"]) != worker_pid:
                        st = status(); c.close(); break
                except Exception:
                    pass
                c.close()
            except Exception:
                pass
        check("frozen worker (SIGSTOP) -> supervisor detects and replaces it", st is not None, f"{time.time()-t0:.0f}s")
        check("hang recorded", st and "hung" in st["last_exit"] and st["restarts"] == n_before + 1, st and st["last_exit"])
        try:
            env.kill(worker_pid, "CONT")
        except Exception:
            pass

        mp = env.model_path()
        if mp:
            os.rename(mp, mp + ".gone")
            env.kill(str(status()["pid"]), "KILL")
            t0 = time.time(); st = None
            while time.time() - t0 < 30:
                try:
                    st = status(); break
                except Exception:
                    time.sleep(0.3)
            s = connect()
            rpc(s, ["C", "enabled", "1"])
            time.sleep(0.5)
            r, dt = timed_rpc(s, ["S", "0", "他在公司当"] + cands)
            check("model file missing -> daemon answers fast without ranking", r[0] == "N" and dt < 50, f"{r} {dt:.0f} ms")
            check("model file missing -> status says not loaded", status()["model_loaded"] is False)
            os.rename(mp + ".gone", mp)
            # 同一配置加载失败后 30 秒内不重试；重启工作进程即可立刻恢复
            env.kill(str(status()["pid"]), "KILL")
            s.close()
            time.sleep(1.2)
            for _ in range(60):
                try:
                    s = connect(); break
                except Exception:
                    time.sleep(0.3)
            check("model restored -> scoring resumes after restart", wait_ready(s) is not None)
            s.close()
finally:
    env.stop()

print("ALL OK" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
