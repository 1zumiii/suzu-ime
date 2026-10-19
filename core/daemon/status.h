// 守护进程的状态：共享计数、状态 JSON、状态文件与令牌文件的写入。
// 状态文件 ai_status.json 由改版 Trime 的 AI 徽标读取，旧字段（ts、role、enabled、model_loaded、student、rss_kb、
// avg_score_ms、skipped、errors、restarts、last_exit 等）的名字与含义保持不变；新增字段放在 engine、client 两个对象里。
#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <sys/types.h>

#include "daemon/options.h"

namespace suzu {
class Engine;
}

namespace suzu::daemon {

struct DaemonState {
    long long start_ms = 0;
    std::atomic<int> clients{0};
    std::atomic<long long> requests{0}, errors{0}, last_request_s{0};
    std::atomic<int> last_score_ms{0};
    std::atomic<bool> screen_off{false};
    std::atomic<long long> fast_status_until_ms{0};   // 手动切换后一小段时间内加快状态文件的刷新
    std::mutex client_mu;
    std::string client_report = "{}";                  // 客户端最近一次上报的计数（JSON 对象）
};

std::string build_daemon_status(const Options & opt, DaemonState & st, Engine & engine, const char * state);
std::string build_supervisor_status(const Options & opt, const char * state, int backoff_s);

std::string status_path(const Options & opt);
std::string token_path(const Options & opt);
void write_file_atomic(const std::string & path, const std::string & data, mode_t mode);
std::string json_escape(const std::string & s);
long read_rss_kb();

}  // namespace suzu::daemon
