// librime_llm.so — Trime 进程里的轻量客户端，对 Lua 暴露与进程内模块同名的接口，推理由守护进程 rimellmd 完成。
// 随 APK 打包，由 package.loadlib("librime_llm.so", "luaopen_rime_llm") 加载。
//
// 时间上限（输入法线程上每个调用最多等多久）：
//   score        第一阶段 budget_ms + 20 ms（默认 65 ms），第二阶段 sync_budget_ms + 20 ms（默认 90 ms）。
//                守护进程自己在 budget_ms 内应答，多出的 20 ms 只在守护进程整个卡住时才会用满。
//   prepare      50 ms；is_ready 50 ms 且结果缓存 300 ms；touch 40 ms 且 1.5 秒最多一次；其余配置类 100~150 ms。
//   连不上       立即返回（指数退避 0.5 s → 10 s 后才再试），候选保持原顺序。
#include <cstring>
#include <string>
#include <vector>

#include "client/connection.h"
#include "ipc/frame.h"
#include "ipc/protocol.h"
#include "lua/lua_common.h"

namespace {

namespace sl = suzu::lua;
using suzu::client::Connection;

constexpr int kMarginMs = 20;
constexpr int kMaxCandidates = 11;

Connection & conn() {
    static Connection * c = new Connection;   // 故意不析构：宿主退出时别的线程可能还在用
    return *c;
}

int g_budget_ms = 45;
int g_sync_budget_ms = 70;
std::string g_model_path;   // 只记录：模型由守护进程的启动参数决定
long long g_max_ctx = 10, g_min_tokens = 1, g_n_threads = 2, g_idle_unload_sec = 0, g_n_ctx = 128, g_n_seq_max = 12;

long long g_ready_at = -100000;
bool g_ready = false;
long long g_touch_at = -100000;
long long g_report_at = 0;

// 客户端看到的计数（顺序与 ipc::kClientCounterNames 一致），定期上报给守护进程并入状态文件
struct ClientCounters {
    long long score_calls = 0, score_ok = 0, skipped = 0, timeouts = 0, down = 0, wait_max_ms = 0, over50 = 0, over100 = 0;
} g_cnt;

void record_wait(long long ms) {
    if (ms > g_cnt.wait_max_ms) g_cnt.wait_max_ms = ms;
    if (ms > 50) g_cnt.over50++;
    if (ms > 100) g_cnt.over100++;
}

void report_counters() {
    std::vector<std::string> req = {"K"};
    const long long v[] = {g_cnt.score_calls, g_cnt.score_ok, g_cnt.skipped, g_cnt.timeouts, g_cnt.down,
                           g_cnt.wait_max_ms, g_cnt.over50, g_cnt.over100};
    for (long long x : v) req.push_back(std::to_string(x));
    std::vector<std::string> rep;
    conn().rpc(req, 40, &rep);
}

int l_score(lua_State * L) {
    size_t cl = 0;
    const char * ctx = luaL_checklstring(L, 1, &cl);
    luaL_checktype(L, 2, LUA_TTABLE);
    bool allow_sync = sl::read_allow_sync(L, 3);
    std::vector<std::string> cands = sl::read_candidates(L, 2, kMaxCandidates);
    g_cnt.score_calls++;
    if (cands.size() < 2) return sl::push_skip(L, "few_candidates");

    int budget = allow_sync ? g_sync_budget_ms : g_budget_ms;
    std::vector<std::string> req = {"Q", std::to_string(budget), std::string(ctx, cl)};
    req.insert(req.end(), cands.begin(), cands.end());
    std::vector<std::string> rep;
    long long t0 = suzu::ipc::mono_ms();
    bool ok = conn().rpc(req, budget + kMarginMs, &rep);
    long long waited = suzu::ipc::mono_ms() - t0;
    record_wait(waited);
    if (!ok) {
        bool timed_out = waited >= budget;
        if (timed_out) g_cnt.timeouts++; else g_cnt.down++;
        return sl::push_skip(L, timed_out ? "timeout" : "down");
    }
    if (rep.empty() || rep[0] != "R") {
        const std::string why = rep.size() >= 2 ? rep[1] : "";
        if (why != "no_context" && why != "few_candidates") g_cnt.skipped++;
        lua_pushnil(L);
        if (why.empty()) return 1;
        lua_pushlstring(L, why.data(), why.size());
        return 2;
    }
    g_cnt.score_ok++;
    std::vector<std::string> ranked;
    std::vector<double> scores;
    for (size_t i = 1; i + 1 < rep.size(); i += 2) {
        ranked.push_back(rep[i]);
        scores.push_back(atof(rep[i + 1].c_str()));
    }
    sl::remember_scores(ranked, scores);
    sl::push_ranking(L, ranked, scores);   // 应答已按分数降序，这里稳定排序不改变顺序
    return 1;
}

int l_get_scores(lua_State * L) { sl::push_last_scores(L); return 1; }

int l_prepare(lua_State * L) {
    size_t cl = 0;
    const char * ctx = luaL_checklstring(L, 1, &cl);
    std::vector<std::string> rep;
    bool ok = conn().rpc({"P", std::string(ctx, cl)}, 50, &rep);
    lua_pushboolean(L, ok && !rep.empty() && rep[0] == "1");
    return 1;
}

int l_is_ready(lua_State * L) {
    long long now = suzu::ipc::mono_ms();
    if (now - g_ready_at >= 300) {   // 短缓存：每次按键都会问
        std::vector<std::string> rep;
        bool ok = conn().rpc({"I"}, 50, &rep);
        g_ready = ok && !rep.empty() && rep[0] == "1";
        g_ready_at = suzu::ipc::mono_ms();
    }
    lua_pushboolean(L, g_ready);
    return 1;
}

int l_touch(lua_State *) {
    long long now = suzu::ipc::mono_ms();
    if (now - g_touch_at < 1500) return 0;   // 合并：守护进程的空闲时限是分钟级
    g_touch_at = now;
    std::vector<std::string> rep;
    conn().rpc({"T"}, 40, &rep);
    if (now - g_report_at >= 5000) { g_report_at = now; report_counters(); }
    return 0;
}

int l_screen_off(lua_State * L) {
    bool off = lua_toboolean(L, 1) != 0;
    std::vector<std::string> rep;
    lua_pushboolean(L, conn().rpc({"Z", off ? "1" : "0"}, 100, &rep));
    return 1;
}

int l_status(lua_State * L) {
    std::vector<std::string> rep;
    if (!conn().rpc({"G"}, 150, &rep) || rep.empty()) { lua_pushnil(L); return 1; }
    lua_pushlstring(L, rep[0].data(), rep[0].size());
    return 1;
}

// 手动启停：关 = 守护进程立刻卸载模型并拒绝推理；开 = 立刻开始加载。守护进程重启后自动重新同步。
int l_set_enabled(lua_State * L) {
    long long v = lua_toboolean(L, 1) ? 1 : 0;
    conn().remember_config("enabled", v, 150);
    g_ready_at = -100000;   // is_ready 缓存失效
    lua_pushboolean(L, conn().connected());
    return 1;
}

int l_connected(lua_State * L) { lua_pushboolean(L, conn().connected()); return 1; }

// 以下与进程内模块保持接口一致（Android 上没有对应能力）
int l_nil(lua_State * L) { lua_pushnil(L); return 1; }
int l_noop(lua_State *) { return 0; }
int l_false(lua_State * L) { lua_pushboolean(L, 0); return 1; }

int l_index(lua_State * L) {
    const char * k = luaL_checkstring(L, 2);
    if (!strcmp(k, "model_path")) lua_pushstring(L, g_model_path.c_str());
    else if (!strcmp(k, "max_ctx")) lua_pushinteger(L, g_max_ctx);
    else if (!strcmp(k, "min_tokens")) lua_pushinteger(L, g_min_tokens);
    else if (!strcmp(k, "n_threads")) lua_pushinteger(L, g_n_threads);
    else if (!strcmp(k, "n_ctx")) lua_pushinteger(L, g_n_ctx);
    else if (!strcmp(k, "n_seq_max")) lua_pushinteger(L, g_n_seq_max);
    else if (!strcmp(k, "idle_unload_sec")) lua_pushinteger(L, g_idle_unload_sec);
    else if (!strcmp(k, "log_dir")) lua_pushstring(L, conn().token_dir().c_str());
    else if (!strcmp(k, "port")) lua_pushinteger(L, conn().port());
    else if (!strcmp(k, "budget_ms")) lua_pushinteger(L, g_budget_ms);
    else if (!strcmp(k, "sync_budget_ms")) lua_pushinteger(L, g_sync_budget_ms);
    else if (!strcmp(k, "use_repack")) lua_pushboolean(L, 0);
    else lua_pushnil(L);
    return 1;
}

// 先按类型取出参数再动 C++ 对象：Lua 报错用 longjmp，不能发生在构造了带析构的对象之后
int l_newindex(lua_State * L) {
    const char * k = luaL_checkstring(L, 2);
    if (!strcmp(k, "model_path")) { const char * v = luaL_checkstring(L, 3); g_model_path = v; return 0; }
    if (!strcmp(k, "log_dir")) { const char * v = luaL_checkstring(L, 3); conn().set_token_dir(v); return 0; }
    if (!strcmp(k, "student_vocab") || !strcmp(k, "use_repack")) return 0;   // 由守护进程的启动参数决定
    lua_Integer v = luaL_checkinteger(L, 3);
    if (!strcmp(k, "port")) conn().set_port((int)v);
    else if (!strcmp(k, "budget_ms")) g_budget_ms = v > 0 ? (int)v : 1;
    else if (!strcmp(k, "sync_budget_ms")) g_sync_budget_ms = v > 0 ? (int)v : 1;
    else if (!strcmp(k, "max_ctx")) { g_max_ctx = v; conn().remember_config("max_ctx", v, 100); }
    else if (!strcmp(k, "min_tokens")) { g_min_tokens = v; conn().remember_config("min_tokens", v, 100); }
    else if (!strcmp(k, "n_threads")) { g_n_threads = v; conn().remember_config("n_threads", v, 100); }
    else if (!strcmp(k, "idle_unload_sec")) { g_idle_unload_sec = v; conn().remember_config("idle_unload_sec", v, 100); }
    else if (!strcmp(k, "n_ctx")) g_n_ctx = v;
    else if (!strcmp(k, "n_seq_max")) g_n_seq_max = v;
    return 0;
}

int l_gc(lua_State *) { conn().close(); return 0; }

}  // namespace

extern "C" __attribute__((visibility("default"))) int luaopen_rime_llm(lua_State * L) {
    lua_newtable(L);
    static const sl::Fn fns[] = {
        {"score", l_score}, {"get_scores", l_get_scores}, {"prepare", l_prepare}, {"is_ready", l_is_ready},
        {"touch", l_touch}, {"screen_off", l_screen_off}, {"set_enabled", l_set_enabled}, {"status", l_status},
        {"connected", l_connected}, {"llm_context", l_nil}, {"kick_context", l_noop}, {"edit_reset_context", l_noop},
        {"fg_changed", l_false}, {"click_happened", l_false}, {"match_code", sl::l_match_code},
        {"now_ms", sl::l_now_ms}, {"sleep_ms", sl::l_sleep_ms},
    };
    sl::set_functions(L, fns, sizeof fns / sizeof fns[0]);
    lua_pushstring(L, sl::kDefaultCodePattern);
    lua_setfield(L, -2, "default_code_pattern");
    lua_pushstring(L, "daemon-client");
    lua_setfield(L, -2, "backend");
    lua_createtable(L, 0, 3);
    lua_pushcfunction(L, l_index);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, l_newindex);
    lua_setfield(L, -2, "__newindex");
    lua_pushcfunction(L, l_gc);
    lua_setfield(L, -2, "__gc");
    lua_setmetatable(L, -2);
    return 1;
}
