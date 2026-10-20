#include "daemon/options.h"

#include <cstdio>
#include <cstdlib>

namespace suzu::daemon {

void print_usage() {
    fprintf(stderr,
            "usage: rimellmd [--supervise] --model PATH [--student-vocab PATH] [--rime-dir DIR] [--port N]\n"
            "                [--log-dir DIR] [--threads N] [--n-seq-max N] [--gen-beam N] [--idle-sec N] [--screen-off-sec N]\n"
            "                [--orphan-sec N] [--budget-ms N] [--sync-budget-ms N] [--repack]\n"
            "                [--heartbeat-ms N] [--max-backoff-s N]   (supervisor timing, for tests)\n");
}

bool parse_options(int argc, char ** argv, Options * o, int * exit_code) {
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        auto need = [&]() -> const char * {
            if (i + 1 >= argc) { fprintf(stderr, "missing value for %s\n", a.c_str()); return nullptr; }
            return argv[++i];
        };
        // 监督进程 → 子进程的专用参数，不进入 raw_args
        if (a == "--supervise") { o->supervise = true; continue; }
        if (a == "--child") { o->child = true; continue; }
        if (a == "--restarts" || a == "--last-exit") {
            const char * v = need();
            if (!v) break;
            if (a == "--restarts") o->restarts = atoi(v); else o->last_exit = v;
            continue;
        }
        if (a == "--repack") { o->repack = true; o->raw_args.push_back(a); continue; }
        const char * v = need();
        if (!v) { print_usage(); *exit_code = 64; return false; }
        if (a == "--port") o->port = atoi(v);
        else if (a == "--model") o->model = v;
        else if (a == "--student-vocab") o->student_vocab = v;
        else if (a == "--rime-dir") o->rime_dir = v;
        else if (a == "--log-dir") o->log_dir = v;
        else if (a == "--threads") o->threads = atoi(v);
        else if (a == "--n-seq-max") o->n_seq_max = atoi(v);
        else if (a == "--gen-beam") o->gen_beam = atoi(v);
        else if (a == "--idle-sec") o->idle_sec = atoi(v);
        else if (a == "--screen-off-sec") o->screen_off_sec = atoi(v);
        else if (a == "--orphan-sec") o->orphan_sec = atoi(v);
        else if (a == "--budget-ms") o->budget_ms = atoi(v);
        else if (a == "--sync-budget-ms") o->sync_budget_ms = atoi(v);
        else if (a == "--heartbeat-ms") o->heartbeat_ms = atoi(v);
        else if (a == "--max-backoff-s") o->max_backoff_s = atoi(v);
        else if (a == "--max-ctx") o->max_ctx = atoi(v);
        else if (a == "--n-ctx") o->n_ctx = atoi(v);
        else {
            fprintf(stderr, "unknown option %s\n", a.c_str());
            print_usage();
            *exit_code = 64;
            return false;
        }
        o->raw_args.push_back(a);
        o->raw_args.push_back(v);
    }
    if (o->model.empty()) { print_usage(); *exit_code = 64; return false; }
    if (o->threads < 1) o->threads = 1;
    if (o->heartbeat_ms < 100) o->heartbeat_ms = 100;
    if (o->max_backoff_s < 1) o->max_backoff_s = 1;
    return true;
}

}  // namespace suzu::daemon
