#include "client/connection.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "ipc/frame.h"

namespace suzu::client {

namespace {
constexpr int kConnectMs = 30;
constexpr int kHelloMs = 80;
constexpr long long kMaxBackoffMs = 10000;
}  // namespace

void Connection::set_token_dir(const std::string & dir) {
    std::lock_guard<std::mutex> lk(mu_);
    dir_ = dir;
    retry_at_ = 0;   // 目录变了，令牌路径也变了：立刻允许重连
    fail_streak_ = 0;
}

std::string Connection::token_dir() const {
    std::lock_guard<std::mutex> lk(mu_);
    return dir_;
}

void Connection::set_port(int port) {
    std::lock_guard<std::mutex> lk(mu_);
    port_ = port;
    close_locked();
    retry_at_ = 0;
}

int Connection::port() const {
    std::lock_guard<std::mutex> lk(mu_);
    return port_;
}

bool Connection::connected() const {
    std::lock_guard<std::mutex> lk(mu_);
    return fd_ >= 0;
}

void Connection::close() {
    std::lock_guard<std::mutex> lk(mu_);
    close_locked();
}

void Connection::close_locked() {
    if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
}

void Connection::fail_locked() {
    close_locked();
    fail_streak_++;
    long long d = 500LL << (fail_streak_ > 5 ? 5 : fail_streak_ - 1);   // 0.5 s → 10 s
    retry_at_ = ipc::mono_ms() + (d > kMaxBackoffMs ? kMaxBackoffMs : d);
}

std::string Connection::read_token_locked() const {
    if (dir_.empty()) return "";
    FILE * f = fopen((dir_ + "/llm_token").c_str(), "r");
    if (!f) return "";
    char b[128] = {0};
    size_t n = fread(b, 1, sizeof b - 1, f);
    fclose(f);
    std::string t(b, n);
    while (!t.empty() && (t.back() == '\n' || t.back() == '\r' || t.back() == ' ')) t.pop_back();
    return t;
}

bool Connection::connect_locked(long long deadline) {
    if (fd_ >= 0) return true;
    if (ipc::mono_ms() < retry_at_) return false;
    std::string token = read_token_locked();
    if (token.empty()) { fail_locked(); return false; }   // 守护进程还没启动过
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) { fail_locked(); return false; }
    fcntl(fd, F_SETFL, O_NONBLOCK);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)port_);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int r = ::connect(fd, (struct sockaddr *)&a, sizeof a);
    if (r != 0 && errno != EINPROGRESS) { ::close(fd); fail_locked(); return false; }
    if (r != 0) {
        if (!ipc::wait_fd(fd, POLLOUT, std::min(deadline, ipc::mono_ms() + kConnectMs))) { ::close(fd); fail_locked(); return false; }
        int err = 0;
        socklen_t el = sizeof err;
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el);
        if (err != 0) { ::close(fd); fail_locked(); return false; }
    }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    // 认证和重新下发配置共用调用方的总时限：配置帧一次发完，再依次收应答
    auto left = [&] { return (int)std::max(1LL, std::min(deadline, ipc::mono_ms() + kHelloMs) - ipc::mono_ms()); };
    std::vector<std::string> rep;
    if (!ipc::send_frame(fd, {"H", token}, left()) || !ipc::recv_frame(fd, rep, left(), left()) || rep.empty() ||
        rep[0] != "OK") {
        ::close(fd);
        fail_locked();
        return false;
    }
    for (auto & kv : config_)
        if (!ipc::send_frame(fd, {"C", kv.first, std::to_string(kv.second)}, left())) { ::close(fd); fail_locked(); return false; }
    for (size_t i = 0; i < config_.size(); i++)
        if (!ipc::recv_frame(fd, rep, left(), left())) { ::close(fd); fail_locked(); return false; }
    fd_ = fd;
    fail_streak_ = 0;
    return true;
}

bool Connection::rpc(const std::vector<std::string> & req, int timeout_ms, std::vector<std::string> * rep) {
    std::lock_guard<std::mutex> lk(mu_);
    const long long deadline = ipc::mono_ms() + timeout_ms;   // 连接、认证、重发配置、请求本身，一共不超过 timeout_ms
    for (int attempt = 0; attempt < 2; attempt++) {
        bool was_connected = fd_ >= 0;
        if (!connect_locked(deadline)) return false;
        long long t0 = ipc::mono_ms();
        int left = (int)(deadline - t0);
        if (left <= 0) return false;   // 连接已建好，留给下一次请求用
        if (ipc::send_frame(fd_, req, left) && ipc::recv_frame(fd_, *rep, left, left)) return true;
        bool quick = ipc::mono_ms() - t0 < 10;   // 立刻失败 = 对端已关闭，而不是超时
        if (was_connected && quick && attempt == 0) { close_locked(); retry_at_ = 0; continue; }
        fail_locked();   // 超时：连接上可能还有迟到的应答，断开重连
        return false;
    }
    return false;
}

void Connection::remember_config(const std::string & key, long long value, int timeout_ms) {
    std::vector<std::string> rep;
    {
        std::lock_guard<std::mutex> lk(mu_);
        config_[key] = value;
    }
    // 现在就试着下发（连不上或在退避期内会立即返回）；连上之后的每次重连还会重新下发
    rpc({"C", key, std::to_string(value)}, timeout_ms, &rep);
}

}  // namespace suzu::client
