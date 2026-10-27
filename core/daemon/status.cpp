#include "daemon/status.h"

#include <cstdio>
#include <ctime>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "ipc/frame.h"
#include "ipc/protocol.h"
#include "runtime/engine.h"

namespace suzu::daemon {

std::string status_path(const Options & o) { return o.rime_dir.empty() ? "" : o.rime_dir + "/ai_status.json"; }
std::string token_path(const Options & o) { return o.rime_dir.empty() ? "" : o.rime_dir + "/llm_token"; }
std::string enabled_path(const Options & o) { return o.rime_dir.empty() ? "" : o.rime_dir + "/ai_enabled.state"; }

bool read_enabled_state(const Options & o, bool * enabled) {
    std::string p = enabled_path(o);
    if (p.empty()) return false;
    FILE * f = fopen(p.c_str(), "rb");
    if (!f) return false;
    char buf[8] = {0};
    size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    if (n == 0 || (buf[0] != '0' && buf[0] != '1')) return false;
    *enabled = buf[0] == '1';
    return true;
}

void write_file_atomic(const std::string & path, const std::string & data, mode_t mode) {
    if (path.empty()) return;
    std::string tmp = path + ".tmp";
    int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (fd >= 0) {
        ssize_t w = write(fd, data.data(), data.size());
        fchmod(fd, mode);
        close(fd);
        if (w == (ssize_t)data.size() && rename(tmp.c_str(), path.c_str()) == 0) return;
        unlink(tmp.c_str());
    }
    // /sdcard 上 rename 偶尔失败：退回直接写
    fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (fd >= 0) {
        ssize_t w = write(fd, data.data(), data.size());
        (void)w;
        close(fd);
    }
}

std::string json_escape(const std::string & s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o.push_back('\\'); o.push_back(c); }
        else if ((unsigned char)c < 0x20) o.push_back(' ');
        else o.push_back(c);
    }
    return o;
}

long read_rss_kb() {
    FILE * f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long v = -1;
    while (fgets(line, sizeof line, f))
        if (sscanf(line, "VmRSS: %ld kB", &v) == 1) break;
    fclose(f);
    return v;
}

std::string build_daemon_status(const Options & opt, DaemonState & st, Engine & engine, const char * state) {
    EngineStats es = engine.stats();
    EngineConfig cfg = engine.config();
    std::string client;
    {
        std::lock_guard<std::mutex> lk(st.client_mu);
        client = st.client_report;
    }
    long long up = (ipc::mono_ms() - st.start_ms) / 1000;
    char head[1200];
    snprintf(head, sizeof head,
             "{\"ts\":%lld,\"role\":\"daemon\",\"state\":\"%s\",\"pid\":%d,\"version\":\"%s\",\"uptime_s\":%lld,"
             "\"enabled\":%s,\"model_loaded\":%s,\"rss_kb\":%ld,\"clients\":%d,\"requests\":%lld,\"errors\":%lld,"
             "\"scores\":%llu,\"skipped\":%llu,\"skip_unprepared\":0,\"skip_busy\":0,\"skip_timeout\":%llu,"
             "\"no_context\":%llu,\"last_score_ms\":%d,\"avg_score_ms\":%.1f,\"max_score_ms\":%.0f,"
             "\"student\":true,\"screen\":\"%s\",\"idle_unload_sec\":%d,\"restarts\":%d,\"last_exit\":\"%s\","
             "\"last_request_s\":%lld,\"port\":%d,",
             (long long)time(nullptr), state, (int)getpid(), ipc::kVersion, up, engine.enabled() ? "true" : "false",
             engine.is_ready() ? "true" : "false", read_rss_kb(), st.clients.load(), st.requests.load(),
             st.errors.load(), (unsigned long long)es.ok, (unsigned long long)es.skipped(),
             (unsigned long long)es.skip_timeout, (unsigned long long)es.no_context, st.last_score_ms.load(),
             es.wait_ms.mean(), es.wait_ms.max(), st.screen_off.load() ? "off" : "on", cfg.idle_unload_sec,
             opt.restarts, json_escape(opt.last_exit).c_str(), st.last_request_s.load(), opt.port);
    return std::string(head) + "\"engine\":{" + engine.status_fields() + "},\"client\":" + client + "}";
}

std::string build_supervisor_status(const Options & opt, const char * state, int backoff_s) {
    char buf[600];
    snprintf(buf, sizeof buf,
             "{\"ts\":%lld,\"role\":\"supervisor\",\"state\":\"%s\",\"pid\":%d,\"version\":\"%s\",\"model_loaded\":false,"
             "\"restarts\":%d,\"last_exit\":\"%s\",\"backoff_s\":%d,\"port\":%d}",
             (long long)time(nullptr), state, (int)getpid(), ipc::kVersion, opt.restarts,
             json_escape(opt.last_exit).c_str(), backoff_s, opt.port);
    return buf;
}

}  // namespace suzu::daemon
