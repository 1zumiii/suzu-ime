// 客户端到守护进程的连接：连接、认证、带时限的请求/应答、失败后的指数退避重连、重连后重新下发配置。
// 线程安全（内部一把锁）；所有操作都有总时限，连不上时立即返回失败，绝不拖住输入法线程。
#pragma once

#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace suzu::client {

class Connection {
public:
    void set_token_dir(const std::string & dir);   // 令牌文件 <dir>/llm_token 所在目录
    std::string token_dir() const;
    void set_port(int port);
    int port() const;

    // 记下一项配置并尝试立即下发；之后每次重连都会重新下发（守护进程重启后默认值会丢）
    void remember_config(const std::string & key, long long value, int timeout_ms);

    // 一次请求/应答，总时限 timeout_ms（含必要的重连）。连接在上次请求之后才断开（守护进程刚重启）时，立即重连重试一次。
    bool rpc(const std::vector<std::string> & req, int timeout_ms, std::vector<std::string> * rep);

    bool connected() const;
    void close();

private:
    bool connect_locked(long long deadline);
    void fail_locked();
    void close_locked();
    std::string read_token_locked() const;

    mutable std::mutex mu_;
    int fd_ = -1;
    long long retry_at_ = 0;
    int fail_streak_ = 0;
    std::string dir_;
    int port_ = 17920;
    std::map<std::string, long long> config_;
};

}  // namespace suzu::client
