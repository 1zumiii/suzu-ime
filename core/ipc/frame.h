// 守护进程与客户端之间的帧读写。帧：4 字节大端长度 + 负载；负载是若干字段，用 0x1F 分隔。
// 全部 I/O 带总时限（poll），任何一端卡住都不会拖住另一端。
#pragma once

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace suzu::ipc {

constexpr char kSep = '\x1f';
constexpr uint32_t kMaxFrame = 64 * 1024;

inline long long mono_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// 等待 fd 可读/可写，直到绝对时刻 deadline（mono_ms）。返回 false 表示超时或出错。
inline bool wait_fd(int fd, short events, long long deadline) {
    for (;;) {
        long long left = deadline - mono_ms();
        if (left <= 0) return false;
        struct pollfd pf = {fd, events, 0};
        int r = poll(&pf, 1, (int)(left > 1000000 ? 1000000 : left));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return false;
        return true;
    }
}

inline bool read_full(int fd, void * buf, size_t n, long long deadline) {
    char * p = static_cast<char *>(buf);
    while (n > 0) {
        if (!wait_fd(fd, POLLIN, deadline)) return false;
        ssize_t k = recv(fd, p, n, 0);
        if (k < 0) { if (errno == EINTR || errno == EAGAIN) continue; return false; }
        if (k == 0) return false;   // 对端关闭
        p += k;
        n -= (size_t)k;
    }
    return true;
}

inline bool write_full(int fd, const void * buf, size_t n, long long deadline) {
    const char * p = static_cast<const char *>(buf);
    while (n > 0) {
        if (!wait_fd(fd, POLLOUT, deadline)) return false;
        ssize_t k = send(fd, p, n, MSG_NOSIGNAL);
        if (k < 0) { if (errno == EINTR || errno == EAGAIN) continue; return false; }
        p += k;
        n -= (size_t)k;
    }
    return true;
}

inline bool send_frame(int fd, const std::vector<std::string> & fields, int timeout_ms) {
    std::string body;
    for (size_t i = 0; i < fields.size(); i++) {
        if (i) body.push_back(kSep);
        for (char c : fields[i]) body.push_back(c == kSep ? ' ' : c);   // 字段里不允许出现分隔符
    }
    if (body.size() > kMaxFrame) return false;
    std::string out(4, '\0');
    uint32_t n = (uint32_t)body.size();
    out[0] = (char)(n >> 24); out[1] = (char)(n >> 16); out[2] = (char)(n >> 8); out[3] = (char)n;
    out += body;
    return write_full(fd, out.data(), out.size(), mono_ms() + timeout_ms);
}

// idle_ms：等第一个字节的时限；一旦开始收，整帧必须在 body_ms 内到齐。
inline bool recv_frame(int fd, std::vector<std::string> & fields, int idle_ms, int body_ms) {
    unsigned char h[4];
    if (!read_full(fd, h, 1, mono_ms() + idle_ms)) return false;
    long long dl = mono_ms() + body_ms;
    if (!read_full(fd, h + 1, 3, dl)) return false;
    uint32_t n = ((uint32_t)h[0] << 24) | ((uint32_t)h[1] << 16) | ((uint32_t)h[2] << 8) | h[3];
    if (n > kMaxFrame) return false;
    std::string body(n, '\0');
    if (n && !read_full(fd, &body[0], n, dl)) return false;
    fields.clear();
    size_t s = 0;
    for (;;) {
        size_t e = body.find(kSep, s);
        if (e == std::string::npos) { fields.push_back(body.substr(s)); break; }
        fields.push_back(body.substr(s, e - s));
        s = e + 1;
    }
    return true;
}

}  // namespace suzu::ipc
