// 监督进程：拉起并看护工作进程。工作进程被杀、崩溃或无响应（连续 3 次心跳无应答，约 10 秒）就带退避重启，
// 并把退出原因记进状态文件（restarts、last_exit）。工作进程以孤儿退出码结束时，监督进程也结束。
#pragma once

#include "daemon/options.h"

namespace suzu::daemon {

int run_supervisor(Options opt);

}  // namespace suzu::daemon
