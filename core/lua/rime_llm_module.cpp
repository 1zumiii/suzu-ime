// 进程内 Lua 模块 rime_llm（macOS 鼠须管、Windows 小狼毫；Linux 上用于测试）。
// 接口与旧核心兼容，见 core/README.md。推理全部交给 runtime/ 的 Engine，本文件只做参数转换。
//
// 可写属性必须走 __index / __newindex，不能预先放进模块表：__newindex 只对表里不存在的键触发，
// 预填充的键被赋值时只是覆盖表项，永远到不了这里的 setter。
#include <cstring>
#include <string>

#include "lua/lua_common.h"
#include "platform/ctxlink.h"
#include "runtime/engine.h"
#include "runtime/log.h"

#if defined(_WIN32)
#define SUZU_EXPORT extern "C" __declspec(dllexport)
#else
#define SUZU_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {

using suzu::Engine;
using suzu::ScoreStatus;
namespace sl = suzu::lua;

// 时限：第一阶段（allow_sync = false）与第二阶段（现场解码另一段上文）分开设
double g_budget_ms = 40;
double g_sync_budget_ms = 70;
int g_open_count = 0;           // 打开的模块表个数；最后一个被回收时停止工作线程并释放模型

// 旧接口里只做记录、对 Suzu 不起作用的属性
long long g_max_ctx = 10, g_min_tokens = 1, g_n_ctx = 128;

Engine & engine() { return suzu::shared_engine(); }

// 没有设置词表时，按模型文件名找同名词表（suzu.gguf → suzu.vocab.txt）
std::string guess_vocab(const std::string & model) {
    if (model.size() < 5 || model.compare(model.size() - 5, 5, ".gguf") != 0) return "";
    std::string v = model.substr(0, model.size() - 5) + ".vocab.txt";
    FILE * f = fopen(v.c_str(), "rb");
    if (!f) return "";
    fclose(f);
    return v;
}

int l_score(lua_State * L) {
    size_t cl = 0;
    const char * ctx = luaL_checklstring(L, 1, &cl);
    luaL_checktype(L, 2, LUA_TTABLE);
    bool allow_sync = sl::read_allow_sync(L, 3);
    auto cands = sl::read_candidates(L, 2, 64);
    // 以下不再调用任何可能 longjmp 的 Lua 函数，直到压栈结果
    suzu::ScoreResult r = engine().score(std::string(ctx, cl), cands, allow_sync ? g_sync_budget_ms : g_budget_ms);
    if (r.status != ScoreStatus::Ok) return sl::push_skip(L, suzu::score_reason(r.status));
    sl::remember_scores(r.cands, r.scores);
    sl::push_ranking(L, r.cands, r.scores);
    return 1;
}

int l_get_scores(lua_State * L) { sl::push_last_scores(L); return 1; }

int l_prepare(lua_State * L) {
    size_t cl = 0;
    const char * ctx = luaL_checklstring(L, 1, &cl);
    lua_pushboolean(L, engine().prepare(std::string(ctx, cl)) ? 1 : 0);
    return 1;
}

int l_is_ready(lua_State * L) { lua_pushboolean(L, engine().is_ready() ? 1 : 0); return 1; }
int l_touch(lua_State *) { engine().touch(); return 0; }

int l_status(lua_State * L) {
    std::string s = "{\"backend\":\"inproc\"," + engine().status_fields() + "}";
    lua_pushlstring(L, s.data(), s.size());
    return 1;
}

int l_llm_context(lua_State * L) {
    std::string text;
    const char * src = nullptr;
    if (suzu::ctxlink::pick(text, src)) {
        lua_pushlstring(L, text.data(), text.size());
        lua_pushstring(L, src);
        return 2;
    }
    lua_pushnil(L);
    return 1;
}
int l_kick_context(lua_State *) { suzu::ctxlink::kick(); return 0; }
int l_edit_reset_context(lua_State *) { suzu::ctxlink::edit_reset(); return 0; }
int l_fg_changed(lua_State * L) { lua_pushboolean(L, suzu::ctxlink::fg_changed() ? 1 : 0); return 1; }
int l_click_happened(lua_State * L) { lua_pushboolean(L, suzu::ctxlink::click_happened() ? 1 : 0); return 1; }

int l_index(lua_State * L) {
    const char * k = luaL_checkstring(L, 2);
    suzu::EngineConfig c = engine().config();
    if (!strcmp(k, "model_path")) lua_pushstring(L, c.model_path.c_str());
    else if (!strcmp(k, "student_vocab")) lua_pushstring(L, c.vocab_path.c_str());
    else if (!strcmp(k, "student_ctx")) lua_pushinteger(L, c.context_chars);
    else if (!strcmp(k, "n_threads")) lua_pushinteger(L, c.n_threads);
    else if (!strcmp(k, "n_seq_max")) lua_pushinteger(L, c.max_candidates + 1);
    else if (!strcmp(k, "idle_unload_sec")) lua_pushinteger(L, c.idle_unload_sec);
    else if (!strcmp(k, "use_repack")) lua_pushboolean(L, c.repack ? 1 : 0);
    else if (!strcmp(k, "log_dir")) lua_pushstring(L, suzu::log_dir().c_str());
    else if (!strcmp(k, "budget_ms")) lua_pushnumber(L, g_budget_ms);
    else if (!strcmp(k, "sync_budget_ms")) lua_pushnumber(L, g_sync_budget_ms);
    else if (!strcmp(k, "max_ctx")) lua_pushinteger(L, g_max_ctx);
    else if (!strcmp(k, "min_tokens")) lua_pushinteger(L, g_min_tokens);
    else if (!strcmp(k, "n_ctx")) lua_pushinteger(L, g_n_ctx);
    else lua_pushnil(L);
    return 1;
}

// 属性赋值。先把参数按类型取出来，再改配置：Lua 报错用 longjmp，不能发生在构造了带析构的 C++ 对象之后。
struct PropValue { const char * s = nullptr; lua_Integer i = 0; lua_Number d = 0; bool b = false; };

void apply_property(const char * k, const PropValue & v) {
    if (!strcmp(k, "log_dir")) { suzu::log_set_dir(v.s); return; }
    if (!strcmp(k, "budget_ms")) { g_budget_ms = v.d; return; }
    if (!strcmp(k, "sync_budget_ms")) { g_sync_budget_ms = v.d; return; }
    if (!strcmp(k, "max_ctx")) { g_max_ctx = v.i; return; }
    if (!strcmp(k, "min_tokens")) { g_min_tokens = v.i; return; }
    if (!strcmp(k, "n_ctx")) { g_n_ctx = v.i; return; }
    suzu::EngineConfig c = engine().config();
    if (!strcmp(k, "model_path")) {
        c.model_path = v.s;
        if (c.vocab_path.empty()) c.vocab_path = guess_vocab(c.model_path);
    }
    else if (!strcmp(k, "student_vocab")) c.vocab_path = v.s;
    else if (!strcmp(k, "student_ctx")) c.context_chars = v.i > 1 ? (int)v.i : 1;
    else if (!strcmp(k, "n_threads")) c.n_threads = v.i > 1 ? (int)v.i : 1;
    else if (!strcmp(k, "n_seq_max")) c.max_candidates = v.i > 2 ? (int)v.i - 1 : 2;   // 旧口径：候选数上限 = n_seq_max - 1
    else if (!strcmp(k, "idle_unload_sec")) c.idle_unload_sec = v.i > 0 ? (int)v.i : 0;
    else if (!strcmp(k, "use_repack")) c.repack = v.b;
    else return;   // 未知属性：忽略（与旧核心一致）
    engine().set_config(c);
}

int l_newindex(lua_State * L) {
    const char * k = luaL_checkstring(L, 2);
    PropValue v;
    static const char * const kStr[] = {"model_path", "student_vocab", "log_dir"};
    static const char * const kNum[] = {"budget_ms", "sync_budget_ms"};
    bool done = false;
    for (const char * n : kStr) if (!strcmp(k, n)) { v.s = luaL_checkstring(L, 3); done = true; }
    for (const char * n : kNum) if (!strcmp(k, n)) { v.d = luaL_checknumber(L, 3); done = true; }
    if (!done && !strcmp(k, "use_repack")) { v.b = lua_toboolean(L, 3) != 0; done = true; }
    if (!done) v.i = luaL_checkinteger(L, 3);   // 其余属性都是整数
    apply_property(k, v);
    return 0;
}

// 模块表被回收（Lua 状态关闭、或 package.loaded 被清掉后的重新初始化）
int l_gc(lua_State *) {
    if (--g_open_count <= 0) {
        g_open_count = 0;
        engine().shutdown();
    }
    return 0;
}

}  // namespace

SUZU_EXPORT int luaopen_rime_llm(lua_State * L) {
    g_open_count++;
    lua_newtable(L);
    static const sl::Fn fns[] = {
        {"score", l_score}, {"get_scores", l_get_scores}, {"prepare", l_prepare}, {"is_ready", l_is_ready},
        {"status", l_status}, {"touch", l_touch}, {"now_ms", sl::l_now_ms}, {"sleep_ms", sl::l_sleep_ms},
        {"match_code", sl::l_match_code}, {"llm_context", l_llm_context}, {"kick_context", l_kick_context},
        {"edit_reset_context", l_edit_reset_context}, {"fg_changed", l_fg_changed},
        {"click_happened", l_click_happened},
    };
    sl::set_functions(L, fns, sizeof fns / sizeof fns[0]);
    lua_pushstring(L, sl::kDefaultCodePattern);
    lua_setfield(L, -2, "default_code_pattern");
    lua_pushstring(L, "inproc");
    lua_setfield(L, -2, "backend");

    lua_createtable(L, 0, 3);
    lua_pushcfunction(L, l_index);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, l_newindex);
    lua_setfield(L, -2, "__newindex");
    lua_pushcfunction(L, l_gc);
    lua_setfield(L, -2, "__gc");
    lua_setmetatable(L, -2);
    // 不在这里加载模型：此时 Lua 侧还没设置 model_path；第一次 prepare / score 时按需加载
    return 1;
}
