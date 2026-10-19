// 进程内模块与守护进程客户端共用的 Lua 绑定代码：与推理无关的小函数、结果的压栈方式、属性表的约定。
#pragma once

#include <string>
#include <vector>

extern "C" {
#include <lauxlib.h>
#include <lua.h>
}

namespace suzu::lua {

// 读取候选表：最多 limit 个；非字符串元素跳过
std::vector<std::string> read_candidates(lua_State * L, int idx, int limit);

// 第三个参数 allow_sync：缺省为 true；只有显式传 false 才算 false
bool read_allow_sync(lua_State * L, int idx);

// 按分数降序（同分保持原顺序）压入排序后的候选表
void push_ranking(lua_State * L, const std::vector<std::string> & cands, const std::vector<double> & scores);

// 压入 nil 与原因码（原因为空时只压 nil），返回值个数
int push_skip(lua_State * L, const char * reason);

// 最近一次打分的结果，供 get_scores() 使用（进程级，带锁）
void remember_scores(const std::vector<std::string> & cands, const std::vector<double> & scores);
void push_last_scores(lua_State * L);

// 与推理无关、两种后端都提供的函数
int l_match_code(lua_State * L);   // match_code(pattern, code)：全串匹配，空模式恒真，非法模式回退默认
int l_now_ms(lua_State * L);       // 单调时钟，毫秒带小数
int l_sleep_ms(lua_State * L);     // 测试用
extern const char * const kDefaultCodePattern;

// 注册一组函数到栈顶的表
struct Fn { const char * name; lua_CFunction fn; };
void set_functions(lua_State * L, const Fn * fns, size_t n);

}  // namespace suzu::lua
