#include "lua/lua_common.h"

#include <algorithm>
#include <chrono>
#include <mutex>
#include <numeric>
#include <regex>
#include <thread>

namespace suzu::lua {

const char * const kDefaultCodePattern = ".{4}";

std::vector<std::string> read_candidates(lua_State * L, int idx, int limit) {
    std::vector<std::string> out;
    int n = (int)lua_rawlen(L, idx);   // 不触发元方法，因此不会抛 Lua 错误
    for (int i = 1; i <= n && (int)out.size() < limit; i++) {
        lua_rawgeti(L, idx, i);
        size_t len = 0;
        const char * s = lua_type(L, -1) == LUA_TSTRING ? lua_tolstring(L, -1, &len) : nullptr;
        if (s) out.emplace_back(s, len);
        lua_pop(L, 1);
    }
    return out;
}

bool read_allow_sync(lua_State * L, int idx) {
    return !(lua_gettop(L) >= idx && lua_isboolean(L, idx) && !lua_toboolean(L, idx));
}

void push_ranking(lua_State * L, const std::vector<std::string> & cands, const std::vector<double> & scores) {
    std::vector<int> order(cands.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return scores[a] > scores[b]; });
    lua_createtable(L, (int)order.size(), 0);
    for (size_t i = 0; i < order.size(); i++) {
        lua_pushlstring(L, cands[order[i]].data(), cands[order[i]].size());
        lua_rawseti(L, -2, (lua_Integer)i + 1);
    }
}

int push_skip(lua_State * L, const char * reason) {
    lua_pushnil(L);
    if (reason && *reason) { lua_pushstring(L, reason); return 2; }
    return 1;
}

int push_generated(lua_State * L, const std::vector<std::string> & words, const std::vector<double> & scores,
                   const std::vector<int> & ranks, int matched) {
    lua_createtable(L, (int)words.size(), 0);
    for (size_t i = 0; i < words.size(); i++) {
        lua_createtable(L, 0, 3);
        lua_pushlstring(L, words[i].data(), words[i].size());
        lua_setfield(L, -2, "w");
        lua_pushnumber(L, i < scores.size() ? scores[i] : 0);
        lua_setfield(L, -2, "s");
        lua_pushinteger(L, i < ranks.size() ? ranks[i] : 0);
        lua_setfield(L, -2, "r");
        lua_rawseti(L, -2, (lua_Integer)i + 1);
    }
    lua_pushinteger(L, matched);
    return 2;
}

namespace {
std::mutex g_scores_mu;
std::vector<std::string> g_last_cands;
std::vector<double> g_last_scores;
}  // namespace

void remember_scores(const std::vector<std::string> & cands, const std::vector<double> & scores) {
    std::lock_guard<std::mutex> lk(g_scores_mu);
    g_last_cands = cands;
    g_last_scores = scores;
}

void push_last_scores(lua_State * L) {
    std::vector<std::string> c;
    std::vector<double> s;
    {
        std::lock_guard<std::mutex> lk(g_scores_mu);
        c = g_last_cands;
        s = g_last_scores;
    }
    lua_createtable(L, 0, (int)c.size());
    for (size_t i = 0; i < c.size() && i < s.size(); i++) {
        lua_pushlstring(L, c[i].data(), c[i].size());
        lua_pushnumber(L, s[i]);
        lua_settable(L, -3);
    }
}

// 与 Rime 自身 auto_select_pattern 同语义（全串匹配）。只在模式变化时重新编译。
int l_match_code(lua_State * L) {
    const char * pat = luaL_optstring(L, 1, nullptr);
    const char * code = luaL_optstring(L, 2, "");
    if (!pat || !*pat) { lua_pushboolean(L, 1); return 1; }
    static std::mutex mu;
    static std::string last_pat;
    static std::regex re;
    static bool valid = false;
    bool ok = false;
    {
        std::lock_guard<std::mutex> lk(mu);
        if (last_pat != pat || !valid) {
            last_pat = pat;
            try {
                re = std::regex(pat, std::regex::ECMAScript);
                valid = true;
            } catch (const std::regex_error &) {
                try { re = std::regex(kDefaultCodePattern, std::regex::ECMAScript); valid = true; }
                catch (...) { valid = false; }
            }
        }
        if (valid) {
            try { ok = std::regex_match(std::string(code), re); } catch (...) { ok = false; }
        }
    }
    lua_pushboolean(L, ok ? 1 : 0);
    return 1;
}

int l_now_ms(lua_State * L) {
    lua_pushnumber(L, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count());
    return 1;
}

int l_sleep_ms(lua_State * L) {
    double ms = luaL_checknumber(L, 1);
    if (ms > 0) std::this_thread::sleep_for(std::chrono::duration<double, std::milli>(ms));
    return 0;
}

void set_functions(lua_State * L, const Fn * fns, size_t n) {
    for (size_t i = 0; i < n; i++) {
        lua_pushcfunction(L, fns[i].fn);
        lua_setfield(L, -2, fns[i].name);
    }
}

}  // namespace suzu::lua
