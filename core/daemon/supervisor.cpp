#include "daemon/supervisor.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "daemon/server.h"
#include "daemon/status.h"
#include "ipc/frame.h"
#include "runtime/log.h"

namespace suzu::daemon {

namespace {

std::atomic<bool> g_stop{false};
void on_term(int) { g_stop.store(true); }

bool ping_child(int port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    fcntl(fd, F_SETFL, O_NONBLOCK);
    bool ok = false;
    int r = connect(fd, (struct sockaddr *)&a, sizeof a);
    if ((r == 0 || errno == EINPROGRESS) && ipc::wait_fd(fd, POLLOUT, ipc::mono_ms() + 1000)) {
        int err = 0;
        socklen_t el = sizeof err;
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el);
        std::vector<std::string> rep;
        ok = err == 0 && ipc::send_frame(fd, {"B"}, 1000) && ipc::recv_frame(fd, rep, 1500, 1500) && !rep.empty() &&
             rep[0] == "PONG";
    }
    close(fd);
    return ok;
}

std::string describe_status(int st) {
    char b[64];
    if (WIFEXITED(st)) { snprintf(b, sizeof b, "exit %d", WEXITSTATUS(st)); return b; }
    if (WIFSIGNALED(st)) {
        int s = WTERMSIG(st);
        const char * n = s == SIGKILL ? "SIGKILL" : s == SIGSEGV ? "SIGSEGV" : s == SIGABRT ? "SIGABRT"
                       : s == SIGBUS ? "SIGBUS" : s == SIGTERM ? "SIGTERM" : s == SIGILL ? "SIGILL" : "signal";
        snprintf(b, sizeof b, "signal %d (%s)", s, n);
        return b;
    }
    return "unknown";
}

bool sleep_interruptible(int ms) {
    for (int i = 0; i < ms / 100 && !g_stop.load(); i++) usleep(100 * 1000);
    return !g_stop.load();
}

}  // namespace

int run_supervisor(Options opt) {
    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);
    mkdir(opt.log_dir.c_str(), 0775);
    log_set_dir(opt.log_dir);
    char self[512];
    ssize_t n = readlink("/proc/self/exe", self, sizeof self - 1);
    if (n <= 0) { fprintf(stderr, "readlink /proc/self/exe failed\n"); return 1; }
    self[n] = 0;
    int backoff = 1;
    log_event("supervisor up");
    while (!g_stop.load()) {
        std::vector<std::string> args = {self, "--child", "--restarts", std::to_string(opt.restarts), "--last-exit", opt.last_exit};
        for (auto & a : opt.raw_args) args.push_back(a);
        std::vector<char *> av;
        for (auto & s : args) av.push_back(const_cast<char *>(s.c_str()));
        av.push_back(nullptr);
        pid_t pid = fork();
        if (pid < 0) { sleep_interruptible(2000); continue; }
        if (pid == 0) { execv(self, av.data()); _exit(127); }

        long long start = ipc::mono_ms();
        long long next_ping = start + 10000;   // 给工作进程留足启动时间
        int fails = 0, st = 0;
        std::string reason;
        for (;;) {
            if (waitpid(pid, &st, WNOHANG) == pid) { reason = describe_status(st); break; }
            if (g_stop.load()) {
                kill(pid, SIGTERM);
                for (int i = 0; i < 30; i++) { if (waitpid(pid, &st, WNOHANG) == pid) return 0; usleep(100 * 1000); }
                kill(pid, SIGKILL);
                waitpid(pid, &st, 0);
                log_event("supervisor stopped");
                return 0;
            }
            long long now = ipc::mono_ms();
            if (now >= next_ping) {
                next_ping = now + 3000;
                fails = ping_child(opt.port) ? 0 : fails + 1;
                if (fails >= 3) {
                    log_event("worker unresponsive, killing");
                    kill(pid, SIGKILL);
                    waitpid(pid, &st, 0);
                    reason = "hung (no heartbeat, killed by supervisor)";
                    break;
                }
            }
            usleep(200 * 1000);
        }
        if (WIFEXITED(st) && WEXITSTATUS(st) == kOrphanExit) {
            log_event("worker exited as orphan (Trime data dir gone); supervisor exiting");
            return 0;
        }
        long long up_s = (ipc::mono_ms() - start) / 1000;
        opt.restarts++;
        opt.last_exit = reason + " after " + std::to_string(up_s) + "s";
        backoff = up_s >= 60 ? 1 : (backoff * 2 > 30 ? 30 : backoff * 2);
        log_event("worker exited: %s; restart in %ds (restarts=%d)", opt.last_exit.c_str(), backoff, opt.restarts);
        write_file_atomic(status_path(opt), build_supervisor_status(opt, "restarting", backoff), 0664);
        if (!sleep_interruptible(backoff * 1000)) break;
    }
    return 0;
}

}  // namespace suzu::daemon
