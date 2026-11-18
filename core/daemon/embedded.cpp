#include "daemon/embedded.h"

#include <cstdio>

#include "daemon/options.h"
#include "daemon/server.h"

namespace suzu::daemon {

int run_embedded(const std::vector<std::string> & args) {
    std::vector<std::string> owned = {"rimellm-embedded"};
    owned.insert(owned.end(), args.begin(), args.end());
    std::vector<char *> argv;
    for (auto & s : owned) argv.push_back(const_cast<char *>(s.c_str()));
    argv.push_back(nullptr);
    Options opt;
    int code = 0;
    if (!parse_options((int)owned.size(), argv.data(), &opt, &code)) return code;
    if (opt.supervise) {
        fprintf(stderr, "--supervise is not supported in embedded mode\n");
        return 64;
    }
    opt.embedded = true;
    opt.child = true;
    // 应用卸载时 Android 会连同进程一起清掉，不需要孤儿检查；状态目录短暂不可读（/sdcard 未挂载）时也不该退出
    opt.orphan_sec = 0;
    return run_worker(opt);
}

}  // namespace suzu::daemon
