// 工作进程：监听 127.0.0.1，认证后把请求转给 Engine，并定期写状态文件。
//
// 每个连接一个线程。连接线程从不自己算：打分交给 Engine 并在时限内等，其余请求立即应答，
// 所以慢打分不会让心跳、预解码、配置这些轻量请求排队。
#pragma once

#include "daemon/options.h"

namespace suzu::daemon {

constexpr int kOrphanExit = 77;   // 因 Trime 被卸载（状态目录长期消失）而退出：监督进程见到它就不再重启

int run_worker(const Options & opt);

}  // namespace suzu::daemon
