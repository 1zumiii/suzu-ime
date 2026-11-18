// 嵌入方式的工作进程：在别的进程里直接跑 run_worker，不经监督进程。
//
// 用途：Android 应用自己的推理服务（独立进程 :suzu_ai 里的绑定服务，见改版 Trime 的 suzu/ai/AppAiService.kt）。
// 进程的重启交给 Android（BIND_AUTO_CREATE），卡死由客户端一侧的看门狗发现后 Process.killProcess，
// 所以这里不需要 supervisor.cpp 的心跳与退避。线路协议、令牌、状态文件与 rimellmd 完全相同，客户端不用改。
#pragma once

#include <string>
#include <vector>

namespace suzu::daemon {

// args 与 rimellmd 的命令行参数相同（不含程序名，不接受 --supervise）。正常情况下不返回（进程被杀时结束），
// 参数错误返回 64，端口以外的启动失败返回非零。
int run_embedded(const std::vector<std::string> & args);

}  // namespace suzu::daemon
