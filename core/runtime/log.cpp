#include "runtime/log.h"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <mutex>

namespace suzu {

namespace {

std::mutex & mu() {
    static std::mutex * m = new std::mutex;   // 故意不析构：进程退出时别的线程可能还在写日志
    return *m;
}
std::string & dir_ref() {
    static std::string * d = new std::string;
    return *d;
}

std::string log_path_locked() {
    std::string d = dir_ref();
    if (d.empty()) {
#ifdef _WIN32
        const char * t = std::getenv("TEMP");
        d = t && *t ? t : ".";
#else
        const char * t = std::getenv("TMPDIR");
        d = t && *t ? t : "/tmp";
#endif
    }
    return d + "/rime_llm_log.txt";
}

}  // namespace

void log_set_dir(const std::string & dir) {
    std::lock_guard<std::mutex> lk(mu());
    dir_ref() = dir;
}

std::string log_dir() {
    std::lock_guard<std::mutex> lk(mu());
    return dir_ref();
}

void log_event(const char * fmt, ...) {
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    char ts[32];
    time_t t = time(nullptr);
    struct tm tmv;
#ifdef _WIN32
    localtime_s(&tmv, &t);
#else
    localtime_r(&t, &tmv);
#endif
    strftime(ts, sizeof ts, "%m-%d %H:%M:%S", &tmv);

    std::lock_guard<std::mutex> lk(mu());
    std::string path = log_path_locked();
    FILE * f = fopen(path.c_str(), "a");
    if (!f) return;
    fprintf(f, "%s %s\n", ts, msg);
    long size = ftell(f);
    fclose(f);
    if (size > 1024 * 1024) {
        std::string old = path + ".old";
        remove(old.c_str());
        rename(path.c_str(), old.c_str());
    }
}

}  // namespace suzu
