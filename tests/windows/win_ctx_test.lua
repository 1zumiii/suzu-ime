-- ai_rerank_app_ctx.lua（Windows、macOS、Android 共用的光标上文接入层）的离线测试（模拟 Rime 环境与 DLL）：上文来源、退回历史、点击与前台切换清历史、编辑键失效快照、预解码用的上文。
-- 用法：lua54 win_ctx_test.lua [rime/lua 目录，默认 rime/lua，在仓库根目录运行] [模块名，默认 ai_rerank_app_ctx]
local dir = arg[1] or "rime/lua"
package.path = dir .. "/?.lua;" .. package.path
local fails = 0
local function check(name, cond) if not cond then fails = fails + 1; print("FAIL " .. name) else print("ok   " .. name) end end

-- 模拟 DLL
local state = { text = "今天天气很", click = false, fg = false, resets = 0, kicks = 0, prepared = {}, ready = true }
local llm = {
  llm_context = function() if state.text then return state.text, "uia" end end,
  click_happened = function() local c = state.click; state.click = false; return c end,
  fg_changed = function() local c = state.fg; state.fg = false; return c end,
  edit_reset_context = function() state.resets = state.resets + 1 end,
  kick_context = function() state.kicks = state.kicks + 1 end,
  prepare = function(c) state.prepared[#state.prepared + 1] = c; return true end,
  is_ready = function() return state.ready end,
}
_G.ai_rerank_llm = llm
_G.ai_rerank_context_get = function() return "上屏历史" end   -- 共用 processor 的原函数

-- 模拟 Rime 的 context 与按键
local options, notifiers = { ai_rerank = true }, {}
local ctx = { input = "" }
function ctx:get_option(n) return options[n] end
function ctx:set_option(n, v) options[n] = v end
ctx.commit_notifier = { connect = function(_, f) notifiers[#notifiers + 1] = f end }
local env = { engine = { context = ctx, schema = { config = { get_string = function() return nil end } } } }
local function key(repr) return { release = function() return false end, repr = function() return repr end } end

local glue = require(arg[2] or "ai_rerank_app_ctx")
glue.init(env)

-- 1) 第一次按键换上函数，UIA 优先并净化
glue.func(key("a"), env)
check("context function replaced", _G.ai_rerank_context_get() == "今天天气很")
-- 2) UIA 读不到：退回上屏历史
state.text = nil
check("falls back to history", _G.ai_rerank_context_get() == "上屏历史")
-- 3) 路径和代码符号截断：只保留靠近光标的自然语言
state.text = "C:\\Users\\a\\b 这是一段话"
check("path is cut", _G.ai_rerank_context_get() == "这是一段话")
-- 4) 不足 2 个字当没有上文，退回历史
state.text = "好"
check("too short falls to history", _G.ai_rerank_context_get() == "上屏历史")
state.text = "今天天气很"
-- 5) 预解码用 filter 实际会用的上文
state.prepared = {}
state.text = "明天天气也"
glue.func(key("b"), env)
check("prepare uses the UIA text", state.prepared[1] == "明天天气也")
glue.func(key("c"), env)
check("no repeated prepare for the same text", #state.prepared == 1)
-- 6) 点击：置 ai_ctx_reset 并让快照失效
state.click = true
glue.func(key("d"), env)
check("click sets ai_ctx_reset", options.ai_ctx_reset == true)
check("click resets snapshot", state.resets == 1)
-- 7) 切换前台应用
options.ai_ctx_reset = nil
state.fg = true
glue.func(key("e"), env)
check("fg change sets ai_ctx_reset", options.ai_ctx_reset == true)
-- 8) 编辑键（没有未上屏编码时）让快照失效；有编码时不动
local before = state.resets
glue.func(key("BackSpace"), env)
check("BackSpace resets snapshot", state.resets == before + 1)
ctx.input = "ni"
glue.func(key("BackSpace"), env)
check("BackSpace with pending input leaves snapshot", state.resets == before + 1)
ctx.input = ""
-- 9) 上屏后 kick
check("commit hook registered", #notifiers == 1)
notifiers[1]()
check("commit kicks the snapshot", state.kicks == 1)
-- 10) 关 AI 时什么都不做
options.ai_rerank = false
state.click = true
glue.func(key("f"), env)
check("AI off: click untouched", state.click == true)

if fails > 0 then print(fails .. " failed"); os.exit(1) end
print("ALL OK")
