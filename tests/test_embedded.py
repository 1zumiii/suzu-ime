#!/usr/bin/env python3
"""嵌入方式的工作进程（Android 应用自己的推理服务走的路径）在主机上的端到端测试。

用法：python3 -I tests/test_embedded.py <rimellm_embedded> <suzu.gguf> <suzu.vocab.txt>
  rimellm_embedded 是 core/ 主机构建里的小程序（daemon/embedded_host.cpp），与 Android 的 JNI 入口
  （daemon/jni_service.cpp）调用同一个 run_embedded。端口 17951，临时目录测完删除。

检查：端口被占时不退出、不写令牌，空出来后接着监听；协议与 rimellmd 相同（认证、打分）；
状态里 host 为 app、没有监督进程；被 kill -9 后不会自己重启（重启交给 Android）。
"""
import json, os, shutil, signal, socket, struct, subprocess, sys, tempfile, time

SEP = "\x1f"
PORT = 17951
fails = 0


def check(name, cond, extra=""):
    global fails
    print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if extra else ""))
    if not cond:
        fails += 1


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


def wait_for(pred, limit):
    t0 = time.time()
    while time.time() - t0 < limit:
        if pred():
            return True
        time.sleep(0.1)
    return False


binp, model, vocab = sys.argv[1:4]
d = tempfile.mkdtemp(prefix="rimellm_embedded_test_")
rime = os.path.join(d, "rime")
os.makedirs(rime)
token_file = os.path.join(rime, "llm_token")
status_file = os.path.join(rime, "ai_status.json")
proc = None
try:
    # 先占住端口：模拟切换方案时 Shizuku 拉起的守护进程还没停
    blocker = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    blocker.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    blocker.bind(("127.0.0.1", PORT))
    blocker.listen(1)

    proc = subprocess.Popen(
        [binp, "--model", model, "--student-vocab", vocab, "--rime-dir", rime, "--log-dir", d, "--port", str(PORT),
         "--threads", "2", "--idle-sec", "0", "--n-seq-max", "10", "--budget-ms", "500", "--sync-budget-ms", "500",
         "--restarts", "3", "--last-exit", "LOW_MEMORY"],
        stdout=open(os.path.join(d, "out.txt"), "w"), stderr=subprocess.STDOUT)
    time.sleep(1.5)
    check("port busy -> keeps running", proc.poll() is None, f"exit {proc.poll()}")
    check("port busy -> no token written yet", not os.path.exists(token_file))
    log = open(os.path.join(d, "rime_llm_log.txt"), encoding="utf-8", errors="replace").read() \
        if os.path.exists(os.path.join(d, "rime_llm_log.txt")) else ""
    check("port busy -> logged retry", "retrying every 2s" in log, log[-200:])

    blocker.close()
    check("port freed -> token written", wait_for(lambda: os.path.exists(token_file), 6))
    token = open(token_file).read().strip() if os.path.exists(token_file) else ""

    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    check("heartbeat", rpc(s, ["B"]) == ["PONG"])
    r = rpc(s, ["H", token])
    check("auth with token", r[0] == "OK" and r[1] == str(proc.pid), str(r))
    rpc(s, ["C", "enabled", "1"])
    ready = wait_for(lambda: rpc(s, ["P", "他在公司当"])[0] == "1" and rpc(s, ["I"])[0] == "1", 30)
    check("model ready", ready)
    r = rpc(s, ["Q", "500", "他在公司当", "经历", "经理"])
    check("score: 经理 above 经历", r[0] == "R" and r[1] == "经理", str(r[:5]))
    st = json.loads(rpc(s, ["G"])[0])
    check("status host=app", st.get("host") == "app", str(st.get("host")))
    check("status restarts/last_exit passed through", st.get("restarts") == 3 and st.get("last_exit") == "LOW_MEMORY",
          f"{st.get('restarts')} {st.get('last_exit')}")
    s.close()
    check("status file written with host=app",
          wait_for(lambda: os.path.exists(status_file) and json.load(open(status_file)).get("host") == "app", 7))

    pids = subprocess.run(["pgrep", "-f", f"rimellm_embedded.*--port {PORT}"], capture_output=True, text=True).stdout.split()
    check("single process (no supervisor)", pids == [str(proc.pid)], str(pids))

    # SIGTERM 不装处理：按默认动作结束（宿主进程的信号归宿主管）
    os.kill(proc.pid, signal.SIGKILL)
    proc.wait(5)
    time.sleep(1.0)
    pids = subprocess.run(["pgrep", "-f", f"rimellm_embedded.*--port {PORT}"], capture_output=True, text=True).stdout.split()
    check("killed -> stays down (restart is the host's job)", pids == [], str(pids))
finally:
    if proc and proc.poll() is None:
        proc.kill()
    shutil.rmtree(d, ignore_errors=True)

print("ALL OK" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
