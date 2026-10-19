// rimellmd — 输入法 AI 重排的常驻推理进程（Android；也可在 Linux 上做主机构建用于测试）。
// 以 shell 身份由 adb 或 Shizuku 拉起，监听 127.0.0.1，Trime 里的客户端 librime_llm.so 通过它打分。
//
// 同一个二进制两种角色：
//   rimellmd --supervise <参数>   监督进程（supervisor.cpp）
//   rimellmd <参数>               工作进程（server.cpp）：推理用 runtime/ 的 Engine，与桌面端是同一份核心
#include "daemon/options.h"
#include "daemon/server.h"
#include "daemon/supervisor.h"

int main(int argc, char ** argv) {
    suzu::daemon::Options opt;
    int code = 0;
    if (!suzu::daemon::parse_options(argc, argv, &opt, &code)) return code;
    if (opt.supervise && !opt.child) return suzu::daemon::run_supervisor(opt);
    return suzu::daemon::run_worker(opt);
}
