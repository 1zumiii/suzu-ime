#include "daemon/server.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "daemon/status.h"
#include "ipc/frame.h"
#include "ipc/protocol.h"
#include "runtime/engine.h"
#include "runtime/log.h"

namespace suzu::daemon {

namespace {

constexpr int kMaxClients = 8;              // 客户端通常只有 1 个
constexpr int kConnIdleMs = 120000;         // 空闲 120 秒没有请求就断开，客户端按需重连
constexpr int kMaxBudgetMs = 1000;

std::atomic<bool> g_stop{false};
std::atomic<bool> g_orphan{false};
void on_term(int) { g_stop.store(true); }

struct Worker {
    const Options & opt;
    Engine & engine;
    DaemonState st;
    std::string token;
    int idle_normal = 0;

    Worker(const Options & o, Engine & e) : opt(o), engine(e) {}

    int effective_idle() const {
        return (st.screen_off.load() && opt.screen_off_sec > 0) ? opt.screen_off_sec : idle_normal;
    }
    void apply_idle() {
        EngineConfig c = engine.config();
        c.idle_unload_sec = effective_idle();
        engine.set_config(c);
    }
    bool token_ok(const std::string & t) const {
        if (t.size() != token.size()) return false;
        unsigned char d = 0;
        for (size_t i = 0; i < t.size(); i++) d |= (unsigned char)(t[i] ^ token[i]);
        return d == 0;
    }

    std::vector<std::string> score(const std::string & ctx, std::vector<std::string> cands, int budget) {
        long long t0 = ipc::mono_ms();
        ScoreResult r = engine.score(ctx, cands, budget);
        st.last_score_ms.store((int)(ipc::mono_ms() - t0));
        if (r.status != ScoreStatus::Ok) return {"N", score_reason(r.status)};
        std::vector<int> order(r.cands.size());
        for (size_t i = 0; i < order.size(); i++) order[i] = (int)i;
        std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return r.scores[a] > r.scores[b]; });
        std::vector<std::string> out = {"R"};
        for (int i : order) {
            char buf[40];
            snprintf(buf, sizeof buf, "%.9g", r.scores[i]);
            out.push_back(r.cands[i]);
            out.push_back(buf);
        }
        return out;
    }

    std::vector<std::string> generate(const std::string & ctx, const std::string & input, int k, int budget) {
        GenResult r = engine.generate(ctx, input, k, budget);
        if (r.status != ScoreStatus::Ok) return {"N", score_reason(r.status)};
        std::vector<std::string> out = {"R"};
        for (size_t i = 0; i < r.words.size(); i++) {
            char buf[40];
            snprintf(buf, sizeof buf, "%.9g", r.scores[i]);
            out.push_back(r.words[i]);
            out.push_back(buf);
            out.push_back(std::to_string(r.ranks[i]));
        }
        return out;
    }

    void configure(const std::string & key, long long v) {
        EngineConfig c = engine.config();
        if (key == "enabled") {
            engine.set_enabled(v != 0);
            st.fast_status_until_ms.store(ipc::mono_ms() + 15000);
            log_event("AI %s (manual)", v ? "enabled" : "disabled");
            return;
        }
        if (key == "idle_unload_sec" && v >= 0 && v <= 86400) { idle_normal = (int)v; apply_idle(); return; }
        if (key == "n_threads" && v >= 1 && v <= 8) { c.n_threads = (int)v; engine.set_config(c); return; }
        if (key == "gen_beam" && v >= 0 && v <= 64) { c.gen_beam = (int)v; engine.set_config(c); return; }
        // max_ctx、min_tokens 是 Qwen 时代的配置，对 Suzu 无作用：接受但忽略
    }

    void handle(int fd) {
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
        bool authed = false;
        std::vector<std::string> f;
        while (!g_stop.load() && ipc::recv_frame(fd, f, kConnIdleMs, 3000) && !f.empty() && !f[0].empty()) {
            st.requests++;
            st.last_request_s.store((long long)time(nullptr));
            char op = f[0][0];
            std::vector<std::string> reply;
            if (op == 'B') {
                reply = {"PONG"};
            } else if (op == 'H') {
                if (f.size() >= 2 && token_ok(f[1])) {
                    authed = true;
                    reply = {"OK", std::to_string((int)getpid()), ipc::kVersion};
                } else {
                    ipc::send_frame(fd, {"ERR", "auth"}, 1000);
                    break;
                }
            } else if (!authed) {
                break;
            } else if (op == 'P' && f.size() >= 2) {
                reply = {engine.prepare(f[1]) ? "1" : "0"};
            } else if (op == 'S' && f.size() >= 3) {
                std::vector<std::string> cands(f.begin() + 3, f.end());
                reply = score(f[2], cands, f[1] == "1" ? opt.sync_budget_ms : opt.budget_ms);
            } else if (op == 'Q' && f.size() >= 3) {
                int budget = atoi(f[1].c_str());
                if (budget <= 0 || budget > kMaxBudgetMs) budget = opt.budget_ms;
                std::vector<std::string> cands(f.begin() + 3, f.end());
                reply = score(f[2], cands, budget);
            } else if (op == 'W' && f.size() >= 5) {
                int budget = atoi(f[1].c_str());
                if (budget <= 0 || budget > kMaxBudgetMs) budget = opt.budget_ms;
                reply = generate(f[3], f[4], atoi(f[2].c_str()), budget);
            } else if (op == 'I') {
                reply = {engine.is_ready() ? "1" : "0"};
            } else if (op == 'T') {
                engine.touch();
                reply = {"OK"};
            } else if (op == 'C' && f.size() >= 3) {
                configure(f[1], atoll(f[2].c_str()));
                reply = {"OK"};
            } else if (op == 'Z' && f.size() >= 2) {
                st.screen_off.store(f[1] == "1");
                apply_idle();
                reply = {"OK"};
            } else if (op == 'G') {
                reply = {build_daemon_status(opt, st, engine, "running")};
            } else if (op == 'K') {
                std::string j = "{";
                for (int i = 0; i < ipc::kClientCounterCount && i + 1 < (int)f.size(); i++) {
                    if (i) j += ",";
                    j += "\"" + std::string(ipc::kClientCounterNames[i]) + "\":" + std::to_string(atoll(f[i + 1].c_str()));
                }
                j += "}";
                std::lock_guard<std::mutex> lk(st.client_mu);
                st.client_report = j;
                reply = {"OK"};
            } else {
                reply = {"ERR", "op"};
            }
            if (!ipc::send_frame(fd, reply, 2000)) break;
        }
        close(fd);
        st.clients--;
    }

    void status_loop() {
        long long next = 0, missing_since = 0;
        while (!g_stop.load()) {
            long long now = ipc::mono_ms();
            // 孤儿保护：Trime 被卸载后它的数据目录会消失，没人能再关掉这个进程
            if (opt.orphan_sec > 0 && !opt.rime_dir.empty()) {
                if (access(opt.rime_dir.c_str(), F_OK) == 0) missing_since = 0;
                else if (missing_since == 0) {
                    missing_since = now;
                    log_event("rime dir is gone; will exit in %ds unless it comes back", opt.orphan_sec);
                } else if (now - missing_since >= (long long)opt.orphan_sec * 1000) {
                    log_event("rime dir gone for %ds: orphaned, exiting", opt.orphan_sec);
                    g_orphan.store(true);
                    g_stop.store(true);
                    break;
                }
            }
            if (now >= next) {
                write_file_atomic(status_path(opt), build_daemon_status(opt, st, engine, "running"), 0664);
                // 平时 5 秒一次（/sdcard 是 fuse，写得越少越省）；手动切换后的 15 秒内 0.3 秒一次
                next = now + (now < st.fast_status_until_ms.load() ? 300 : 5000);
            }
            usleep(100 * 1000);
        }
    }
};

std::string make_token() {
    unsigned char b[16] = {0};
    FILE * f = fopen("/dev/urandom", "rb");
    if (f) { size_t r = fread(b, 1, sizeof b, f); (void)r; fclose(f); }
    static const char * hex = "0123456789abcdef";
    std::string t;
    for (unsigned char c : b) { t.push_back(hex[c >> 4]); t.push_back(hex[c & 15]); }
    return t;
}

std::string guess_vocab(const std::string & model) {
    if (model.size() > 5 && model.compare(model.size() - 5, 5, ".gguf") == 0) {
        std::string v = model.substr(0, model.size() - 5) + ".vocab.txt";
        if (access(v.c_str(), R_OK) == 0) return v;
    }
    return "";
}

}  // namespace

int run_worker(const Options & opt) {
    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);
    mkdir(opt.log_dir.c_str(), 0775);
    log_set_dir(opt.log_dir);

    Engine & engine = shared_engine();
    Worker w(opt, engine);
    w.st.start_ms = ipc::mono_ms();
    w.idle_normal = opt.idle_sec;

    EngineConfig c;
    c.model_path = opt.model;
    c.vocab_path = opt.student_vocab.empty() ? guess_vocab(opt.model) : opt.student_vocab;
    c.n_threads = opt.threads;
    c.max_candidates = opt.n_seq_max > 2 ? opt.n_seq_max - 1 : 11;
    c.repack = opt.repack;
    c.idle_unload_sec = opt.idle_sec;
    if (!opt.rime_dir.empty() && opt.gen_beam > 0) {   // 候选生成：词典就是随方案部署的雾凇词典
        c.lexicon_dir = opt.rime_dir + "/cn_dicts";
        c.gen_beam = opt.gen_beam;
    }
    engine.set_config(c);
    // 默认关闭，与手机上方案开关 ai_rerank 的默认值一致；客户端同步开关后才加载
    engine.set_enabled(false);
    if (c.vocab_path.empty()) log_event("no student vocab: reranking stays off (only Suzu models are supported)");

    w.token = make_token();
    write_file_atomic(token_path(opt), w.token, 0660);

    int lfd = socket(AF_INET, SOCK_STREAM, 0);   // SOCK_CLOEXEC 与 accept4 在 macOS 上没有，统一用 fcntl 设置
    if (lfd >= 0) fcntl(lfd, F_SETFD, FD_CLOEXEC);
    int one = 1;
    setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)opt.port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(lfd, (struct sockaddr *)&a, sizeof a) != 0 || listen(lfd, 8) != 0) {
        fprintf(stderr, "bind/listen 127.0.0.1:%d failed: %s\n", opt.port, strerror(errno));
        log_event("bind/listen 127.0.0.1:%d failed", opt.port);
        return 2;
    }
    log_event("listening on 127.0.0.1:%d threads=%d idle=%ds restarts=%d last_exit=%s", opt.port, opt.threads,
              opt.idle_sec, opt.restarts, opt.last_exit.c_str());

    std::thread([&w] { w.status_loop(); }).detach();
    while (!g_stop.load()) {
        if (!ipc::wait_fd(lfd, POLLIN, ipc::mono_ms() + 500)) continue;   // 超时：回去检查 g_stop
        int fd = accept(lfd, nullptr, nullptr);
        if (fd >= 0) fcntl(fd, F_SETFD, FD_CLOEXEC);
        if (fd < 0) { if (errno == EMFILE || errno == ENFILE) usleep(200 * 1000); continue; }
        if (w.st.clients.load() >= kMaxClients) { close(fd); continue; }
        w.st.clients++;
        std::thread([&w, fd] { w.handle(fd); }).detach();
    }
    log_event("stopping");
    write_file_atomic(status_path(opt), build_daemon_status(opt, w.st, engine, "stopped"), 0664);
    close(lfd);
    engine.shutdown();
    // 连接线程是分离的、可能还在等 recv：直接退出进程，不跑静态析构
    _exit(g_orphan.load() ? kOrphanExit : 0);
}

}  // namespace suzu::daemon
