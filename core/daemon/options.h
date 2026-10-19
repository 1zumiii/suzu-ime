// 守护进程的命令行参数。与旧版 rimellmd 兼容：改版 Trime 的启动脚本按这些参数拉起它，参数名不能改。
#pragma once

#include <string>
#include <vector>

namespace suzu::daemon {

struct Options {
    bool supervise = false;
    bool child = false;
    int port = 17920;
    std::string model;
    std::string student_vocab;        // 为空时按模型文件名找同名词表（suzu.gguf → suzu.vocab.txt）
    std::string rime_dir;             // 状态文件、令牌文件所在目录（Trime 的 rime 用户目录）
    std::string log_dir = "/data/local/tmp/rimellm";
    int threads = 2;
    int n_seq_max = 0;                // 旧口径：候选上限 = n_seq_max - 1；0 = 默认（11 个候选）
    bool repack = false;
    int idle_sec = 0;                 // 屏幕亮着时的空闲卸载时限；0 = 不自动卸载
    int screen_off_sec = 0;           // 熄屏后的空闲卸载时限；0 = 沿用亮屏时限
    int orphan_sec = 600;             // 状态目录消失超过这么久就退出；0 = 不检查
    int budget_ms = 45;               // 版本 1 客户端（S 请求）的打分时限：allow_sync = false
    int sync_budget_ms = 80;          //                                   allow_sync = true
    // 监督进程传给子进程
    int restarts = 0;
    std::string last_exit = "none";
    std::vector<std::string> raw_args;   // 原样传给子进程的参数
    // 只为兼容旧参数而接受、对 Suzu 无作用
    int max_ctx = 10, n_ctx = 0;
};

// 解析失败时打印用法并返回 false（exit_code 置为 64）
bool parse_options(int argc, char ** argv, Options * out, int * exit_code);
void print_usage();

}  // namespace suzu::daemon
