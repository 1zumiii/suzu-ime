-- 同一个 Lua 状态里有多个会话、或会话中途换方案时的检查。用桩模块代替核心（手机客户端库的接口），不需要模型。
-- librime-lua 的 Lua 状态由所有会话共用：模块只加载一次，但通知接线与方案配置要跟着每个会话走。
-- 用法：lua session_test.lua <仓库目录> <临时目录>
local repo, tmp = arg[1], arg[2]
package.path = repo .. "/rime/lua/?.lua;" .. package.path
os.execute("rm -rf '" .. tmp .. "' && mkdir -p '" .. tmp .. "'")

local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end

-- ---------- 模拟 Rime 环境 ----------
local yielded
function yield(c) yielded[#yielded + 1] = c end
rime_api = { get_user_data_dir = function() return tmp end }
local function mk(text, s, e) return { text = text, _start = s, _end = e, type = "phrase", comment = "" } end
function Candidate(_, s, e, text) return mk(text, s, e) end
function ShadowCandidate(c, _, text) return mk(text, c._start, c._end) end

-- 通知：connect 返回可断开的连接（与 librime-lua 一致）
local function notifier()
  local hs = {}
  return {
    connect = function(_, f)
      local h = { f = f }
      hs[#hs + 1] = h
      return { disconnect = function() h.f = nil end }
    end,
    fire = function(...) for _, h in ipairs(hs) do if h.f then h.f(...) end end end,
    live = function() local n = 0; for _, h in ipairs(hs) do if h.f then n = n + 1 end end; return n end,
  }
end
local function mk_ctx()
  local opts = {}
  local ctx
  ctx = {
    input = "",
    option_update_notifier = notifier(), select_notifier = notifier(), commit_notifier = notifier(),
    commit_history = { to_table = function() return {} end },
    get_option = function(_, n) return opts[n] or false end,
    set_option = function(_, n, v) opts[n] = v; ctx.option_update_notifier.fire(ctx, n) end,
  }
  return ctx
end
local function mk_env(ctx, sid, conf)
  conf = conf or {}
  return { engine = { context = ctx, schema = { schema_id = sid, config = {
    get_string = function(_, k) return type(conf[k]) == "string" and conf[k] or nil end,
    get_int = function(_, k) return type(conf[k]) == "number" and math.floor(conf[k]) or nil end,
    get_double = function(_, k) return type(conf[k]) == "number" and conf[k] or nil end,
    get_bool = function(_, k) if type(conf[k]) == "boolean" then return conf[k] end return nil end,
  } } } }
end

-- ---------- 桩核心：手机客户端库的接口（有 set_enabled、screen_off、set_lexicon）----------
local log = { enabled = {}, screen = {}, lexicon = {}, scored = 0 }
local daemon_enabled, opened, last = true, 0, {}
package.preload["rime_llm"] = function()
  opened = opened + 1
  return {
    set_enabled = function(v) log.enabled[#log.enabled + 1] = v; daemon_enabled = v end,
    screen_off = function(v) log.screen[#log.screen + 1] = v end,
    set_lexicon = function(name) log.lexicon[#log.lexicon + 1] = name end,
    status = function() return '{"student":true,"enabled":' .. tostring(daemon_enabled) .. '}' end,
    prepare = function() return true end, is_ready = function() return true end, touch = function() end,
    score = function(_, cands) log.scored = #cands; last = {}; for i, w in ipairs(cands) do last[w] = -i end; return cands end,
    get_scores = function() return last end,
    match_code = function(_, code) return #code >= 2 end,
  }
end

local processor = require("ai_rerank_processor")
local filter = require("ai_rerank_filter")

-- ---------- 一、两个会话 ----------
local ctx1, ctx2 = mk_ctx(), mk_ctx()
local env1 = mk_env(ctx1, "a", { ["llm_rerank/max_candidates"] = 3 })
local env2 = mk_env(ctx2, "a", { ["llm_rerank/max_candidates"] = 3 })
processor.init(env1)
check("第一个会话：模块已加载", _G.ai_rerank_llm ~= nil and opened == 1, tostring(opened))
check("第一个会话：方案开关与守护进程对齐", ctx1:get_option("ai_rerank") == true)
processor.init(env2)
check("第二个会话：模块不重复加载", opened == 1, tostring(opened))
check("第二个会话：方案开关与守护进程对齐", ctx2:get_option("ai_rerank") == true)
check("第二个会话：有对齐函数", type(env2.switch_align) == "function")

log.screen = {}
ctx2:set_option("screen_off", true)
check("第二个会话：熄屏转给守护进程", #log.screen == 1 and log.screen[1] == true, tostring(#log.screen))

log.enabled = {}
ctx2:set_option("_ai_manual", true)
ctx2:set_option("ai_rerank", false)
check("第二个会话：手动关 AI 转给守护进程", #log.enabled == 1 and log.enabled[1] == false, tostring(#log.enabled))
ctx2:set_option("_ai_manual", true)
ctx2:set_option("ai_rerank", true)
check("第二个会话：手动开 AI 转给守护进程", #log.enabled == 2 and log.enabled[2] == true, tostring(#log.enabled))

log.screen = {}
ctx1:set_option("screen_off", false)
check("第一个会话的接线仍在", #log.screen == 1 and log.screen[1] == false, tostring(#log.screen))

-- ---------- 二、同一个会话换方案：组件重建，上下文不变 ----------
local opt_before = ctx2.option_update_notifier.live()
if processor.fini then processor.fini(env2) end
local env3 = mk_env(ctx2, "b", { ["llm_rerank/max_candidates"] = 6, ["llm_rerank/lexicon_dir"] = "cn_dicts_frost" })
log.lexicon = {}
processor.init(env3)
check("换方案：词典目录跟着换", log.lexicon[#log.lexicon] == "cn_dicts_frost", tostring(log.lexicon[#log.lexicon]))
check("换方案：旧组件的通知已断开，不重复接线", ctx2.option_update_notifier.live() == opt_before,
  string.format("%d -> %d", opt_before, ctx2.option_update_notifier.live()))
log.screen = {}
ctx2:set_option("screen_off", true)
check("换方案：熄屏只转一次", #log.screen == 1, tostring(#log.screen))
log.enabled = {}
ctx2:set_option("_ai_manual", true)
ctx2:set_option("ai_rerank", false)
check("换方案：手动关 AI 只转一次", #log.enabled == 1, tostring(#log.enabled))
ctx2:set_option("_ai_manual", true)
ctx2:set_option("ai_rerank", true)

-- 换回方案 a：词典目录回到默认
if processor.fini then processor.fini(env3) end
log.lexicon = {}
local env4 = mk_env(ctx2, "a", { ["llm_rerank/max_candidates"] = 3 })
processor.init(env4)
check("换回原方案：词典目录回到默认", log.lexicon[#log.lexicon] == "cn_dicts", tostring(log.lexicon[#log.lexicon]))

-- ---------- 三、过滤器的配置跟着方案走 ----------
_G.ai_rerank_context_get = function() return "今天天气很好我们一起" end
local function run(env)
  local l = {}
  for i = 1, 12 do l[i] = mk("偶" .. utf8.char(0x4E00 + i), 0, 2) end
  yielded = {}
  local idx = 0
  env.engine.context.input = "ou"
  log.scored = 0
  _G.llm_filter_cache = nil
  filter({ iter = function() return function() idx = idx + 1; return l[idx] end end }, env)
  return log.scored
end
check("方案 a：打分 3 个候选", run(env4) == 3, tostring(log.scored))
check("方案 b：打分 6 个候选", run(env3) == 6, tostring(log.scored))
check("回到方案 a：仍是 3 个", run(env1) == 3, tostring(log.scored))
local envc = mk_env(ctx1, "c", {})
check("方案 c 没有配置：用默认值 10，不沿用上一个方案的", run(envc) == 10, tostring(log.scored))
check("测试脚本拿到的是当前方案的配置表", _G.ai_rerank_cfg.max_candidates == 10)

print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
os.exit(fails == 0 and 0 or 1)
