// 主机上测试嵌入方式用的小程序：参数同 rimellmd（不含 --supervise），直接调用 run_embedded，
// 走的是 Android 推理服务的 JNI 入口（daemon/jni_service.cpp）同一条路径。见 tests/test_embedded.py。
#include <string>
#include <vector>

#include "daemon/embedded.h"

int main(int argc, char ** argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    return suzu::daemon::run_embedded(args);
}
