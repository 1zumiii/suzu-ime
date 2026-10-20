-- 回放数据的端到端测试：用模拟的 Rime 环境驱动真实的过滤器、处理器与推理核心，打开语料开关后选词若干次，
-- 检查 select_replay.jsonl 的内容，并要求 tools/replay.py 用记录里的参数逐条复现最终首位（--check）。
-- 覆盖：融合各项、模糊音扣分、怪词、门控、第二阶段、运行中改参数。
-- 用法：lua replay_test.lua <仓库目录> <rime_llm.so 所在目录> <suzu.gguf> <suzu.vocab.txt> <临时目录>
local repo, so_dir, model, vocab, ud = arg[1], arg[2], arg[3], arg[4], arg[5]
package.path = repo .. "/rime/lua/?.lua;" .. package.path
package.cpath = so_dir .. "/?.so;" .. package.cpath
local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end
os.execute("rm -rf '" .. ud .. "' && mkdir -p '" .. ud .. "'")
io.open(ud .. "/select_corpus.enabled", "w"):close()
local wf = io.open(ud .. "/weird_words.tsv", "w"); wf:write("今里\n"); wf:close()
-- 候选生成用的小词典（格式同雾凇 cn_dicts：YAML 头以 ... 结束，之后每行 词<Tab>拼音<Tab>词频）
os.execute("mkdir -p '" .. ud .. "/cn_dicts'")
local df = io.open(ud .. "/cn_dicts/base.dict.yaml", "w")
df:write("---\nname: base\n...\n经理\tjing li\t100\n精力\tjing li\t90\n经历\tjing li\t80\n教练\tjiao lian\t50\n",
         "家里\tjia li\t40\n今里\tjin li\t30\n局里\tju li\t10\n奖励\tjiang li\t60\n")
df:close()

local m = require("rime_llm")

-- ---------- 模拟 Rime 环境 ----------
local yielded
function yield(c) yielded[#yielded + 1] = c end
rime_api = { get_user_data_dir = function() return ud end, get_distribution_code_name = function() return "Squirrel" end }
local function mk(text, s, e, ctype, comment, quality)
  return { text = text, _start = s, _end = e, type = ctype or "phrase", comment = comment or "", quality = quality or 0 }
end
function Candidate(ctype, s, e, text, comment) return mk(text, s, e, ctype, comment) end
function ShadowCandidate(c, ctype, text, comment) return mk(text, c._start, c._end, ctype, comment, c.quality) end
local function translation(list)
  local i = 0
  return { iter = function() return function() i = i + 1; return list[i] end end }
end
local tail_cands = {}
Component = { Translator = function() return { query = function(_, rest) return translation(tail_cands[rest] or {}) end } end }

local conf = {
  ["llm_rerank/max_candidates"] = 5, ["llm_rerank/select_anchor_rate"] = 1.0,
  ["llm_rerank/student_model"] = model, ["llm_rerank/student_vocab"] = vocab, ["llm_rerank/model_path"] = model,
  ["llm_rerank/cpu_cores"] = 2, ["llm_rerank/generate"] = true, ["llm_rerank/gen_beam"] = 8,
}
local hist = {}
ai_on = true
local select_cb
local ctx
local opt_cbs, reset_flag = {}, false
ctx = {
  input = "",
  commit_history = { to_table = function() local t = {}; for i, w in ipairs(hist) do t[i] = { text = w } end; return t end },
  composition = { back = function(self) return { selected_index = ctx._sel or 0 } end },
  get_option = function(_, name) return (name == "ai_rerank" and ai_on) or name == "ai_stage2" or (name == "ai_ctx_reset" and reset_flag) end,
  set_option = function(_, name, v) if name == "ai_ctx_reset" then reset_flag = v end end,
  get_selected_candidate = function() return ctx._cand end,
  option_update_notifier = { connect = function(_, f) opt_cbs[#opt_cbs + 1] = f end },
  select_notifier = { connect = function(_, f) select_cb = f end },
  commit_notifier = { connect = function() end },
}
local env = { engine = { context = ctx, schema = { config = {
  get_string = function(_, k) return conf[k] and tostring(conf[k]) or nil end,
  get_int = function(_, k) return type(conf[k]) == "number" and math.floor(conf[k]) or nil end,
  get_double = function(_, k) return type(conf[k]) == "number" and conf[k] or nil end,
  get_bool = function(_, k) return conf[k] end,
} } } }

local filter = require("ai_rerank_filter")
local processor = require("ai_rerank_processor")
processor.init(env)
local function key(repr) processor({ release = function() return false end, repr = function() return repr end }, env) end
local function sleep_ms(ms) if m.sleep_ms then m.sleep_ms(ms) else os.execute(string.format("sleep %.3f", ms / 1000)) end end

-- 等模型就绪（有上文时处理器才会发预解码，从而触发加载）
hist = { "今天" }
local t0 = os.time()
while not (m.is_ready and m.is_ready()) do
  key("a"); sleep_ms(200)
  if os.time() - t0 > 60 then print("FAIL: model never ready"); os.exit(1) end
end

-- 运行过滤器，直到第一阶段拿到结果（上文刚变时要等预解码）
local function run_filter(input, list)
  ctx.input = input
  for _ = 1, 50 do
    key("a")
    yielded = {}
    filter(translation(list), env)
    local s1 = _G.ai_select_info and _G.ai_select_info.s1 or ""
    if not s1:match("^skip") then return yielded end
    sleep_ms(50)
  end
  return yielded
end

local function select(input, list, idx)
  local out = run_filter(input, list)
  ctx._cand, ctx._sel = out[idx], idx - 1
  select_cb(ctx)
  hist[#hist + 1] = out[idx].text
  ctx.input = ""
  return out
end

local C = _G.ai_rerank_cfg
C.ranker = "legacy"   -- 默认已是统一排序；先测旧排序的记录，再切到统一排序
local PY = { "我们明天去公司开会", "这个问题需要", "他在公司当", "最近工作压力大没有" }
local JL = {
  mk("经理", 0, 6, "phrase", "［jing li］", 1.2), mk("精力", 0, 6, "phrase", "［jing li］", 1.1),
  mk("经历", 0, 6, "phrase", "［jing li］", 1.0), mk("今里", 0, 6, "phrase", "［jin li］", 0.5),
  mk("近里", 0, 6, "phrase", "［jin li］", 0.4), mk("井里", 0, 6, "phrase", "［jing li］", 0.3),
}
local n_sel, n_s2 = 0, 0
for round = 1, 4 do
  -- 运行中改参数：回放必须按每条记录里的参数重算
  C.freq_beta = ({ 1.5, 0.5, 0, 1.5 })[round]
  C.gate_margin = ({ 0, 0.8, 0, 3 })[round]
  C.fuzzy_penalty = ({ 1, 0, 2, 1 })[round]
  _G.ai_rerank_weird_reload()
  for i, c in ipairs(PY) do
    hist[#hist + 1] = c
    -- 单段：jingli，选第 1 位或第 2 位
    select("jingli", JL, (i + round) % 2 + 1)
    n_sel = n_sel + 1
    -- 四字：原首位是 Rime 造句，第一段只覆盖前 6 个字母，剩余拼音交给第二阶段
    local list = { mk("经理过来", 0, 12, "sentence", "", 2.0) }
    for _, c2 in ipairs(JL) do list[#list + 1] = mk(c2.text, 0, 6, "phrase", c2.comment, c2.quality) end
    tail_cands["guolai"] = { mk("过来", 6, 12), mk("国来", 6, 12), mk("过赖", 6, 12) }
    local out = select("jingliguolai", list, 1)
    if out[1].comment == C.badge2 then n_s2 = n_s2 + 1 end
    n_sel = n_sel + 1
  end
end

-- 统一排序：两轮开 AI，一轮关 AI（λ = 0 照样排序并记录）
C.ranker = "unified"
local n_uni_s2 = 0
for round = 1, 3 do
  ai_on = round <= 2
  C.uni.alpha = ({ 1, 0.3, 1 })[round]
  for i, c in ipairs(PY) do
    hist[#hist + 1] = c
    select("jingli", JL, (i + round) % 2 + 1)
    n_sel = n_sel + 1
    local list = { mk("经理过来", 0, 12, "sentence", "", 2.0) }
    for _, c2 in ipairs(JL) do list[#list + 1] = mk(c2.text, 0, 6, "phrase", c2.comment, c2.quality) end
    tail_cands["guolai"] = { mk("过来", 6, 12), mk("国来", 6, 12), mk("过赖", 6, 12) }
    local out = select("jingliguolai", list, 1)
    for _, c2 in ipairs(out) do if c2.comment == C.badge2 then n_uni_s2 = n_uni_s2 + 1 end end
    n_sel = n_sel + 1
  end
end
ai_on = true

-- 统一排序 + 候选生成：简拼 jl，Rime 只给了前三个（经理、精力、经历），其余由核心在小词典里生成；怪词“今里”不出现
local t1 = os.time()
while true do   -- 词典索引在后台加载
  local _, why = m.generate("他在公司当", "jl", 3, true)
  if why ~= "no_lexicon" or os.time() - t1 > 20 then break end
  sleep_ms(50)
end
C.uni.alpha = 1
local JL3 = { mk("经理", 0, 2, "phrase", "［jing li］", 1.2), mk("精力", 0, 2, "phrase", "［jing li］", 1.1),
              mk("经历", 0, 2, "phrase", "［jing li］", 1.0) }
local n_gen_seen, weird_seen, last_pick = 0, false, nil
for i, c in ipairs(PY) do
  hist[#hist + 1] = c
  local out = run_filter("jl", JL3)
  for _, c2 in ipairs(out) do
    if c2.text == "教练" or c2.text == "家里" or c2.text == "奖励" or c2.text == "局里" then n_gen_seen = n_gen_seen + 1 end
    if c2.text == "今里" then weird_seen = true end
  end
  -- 选一个与上一轮不同的词（同一输入、同一个词在 1 秒内的两次通知会被当作重复，只记一次）
  local k = (i % 2) + 1
  if out[k].text == last_pick then k = 3 - k end
  last_pick = out[k].text
  ctx._cand, ctx._sel = out[k], k - 1
  select_cb(ctx)
  hist[#hist + 1] = out[k].text
  ctx.input = ""
  n_sel = n_sel + 1
end
check("生成：Rime 没给的词进入候选窗，怪词不出现", n_gen_seen > 0 and not weird_seen, "gen=" .. n_gen_seen)

-- ---------- 检查回放数据 ----------
local lines = {}
for ln in io.lines(ud .. "/select_replay.jsonl") do lines[#lines + 1] = ln end
check("每次选词各写一行回放数据", #lines == n_sel, #lines .. "/" .. n_sel)
local any_g, any_fz, any_wd, any_s2, any_q = false, false, false, false, false
for _, ln in ipairs(lines) do
  if ln:find('"g":%[') then any_g = true end
  if ln:find('"fz":1') then any_fz = true end
  if ln:find('"wd":1') then any_wd = true end
  if ln:find('"s2":"') then any_s2 = true end
  if ln:find('"q":1%.2') then any_q = true end
end
check("有打分组、模糊音、怪词、quality 字段", any_g and any_fz and any_wd and any_q)
check("第二阶段生效过并被记录", n_s2 > 0 and any_s2, "stage2=" .. n_s2)
local n_uni, n_cmp, n_lam0 = 0, 0, 0
for _, ln in ipairs(lines) do
  if ln:find('"uni":1') then n_uni = n_uni + 1 end
  if ln:find('"s2cmp":1') then n_cmp = n_cmp + 1 end
  if ln:find('"uni":1') and ln:find('"lam":0[,}]') then n_lam0 = n_lam0 + 1 end
end
local n_genrec = 0
for _, ln in ipairs(lines) do if ln:find('"inr":0') and ln:find('"gen":1') then n_genrec = n_genrec + 1 end end
check("生成：回放数据带生成标记", n_genrec >= 1, "records=" .. n_genrec)
check("统一排序：记录、与整句比分、关 AI 时 λ = 0", n_uni == 28 and n_cmp > 0 and n_lam0 >= 8 and n_uni_s2 > 0,
  string.format("uni=%d cmp=%d lam0=%d s2=%d", n_uni, n_cmp, n_lam0, n_uni_s2))
local nc = 0
for _ in io.lines(ud .. "/select_corpus.tsv") do nc = nc + 1 end
check("语料与回放数据同样条数", nc == #lines, nc .. "/" .. #lines)

-- 失步（点击光标等，运行时选项 ai_ctx_reset）之后上文为空：下一次输入不推理，候选保持 Rime 原顺序
do
  hist = { "他在公司当" }
  key("a")
  check("失步：失步前有上文", _G.ai_rerank_context_get() ~= "", _G.ai_rerank_context_get())
  reset_flag = true
  for _, f in ipairs(opt_cbs) do f(ctx, "ai_ctx_reset") end
  check("失步：上文清空，选项复位", _G.ai_rerank_context_get() == "" and reset_flag == false, tostring(_G.ai_rerank_context_get()))
  ctx.input = "jingli"
  yielded = {}
  filter(translation(JL), env)
  local order = {}
  for i, c in ipairs(yielded) do order[i] = c.text end
  local s1 = _G.ai_select_info and _G.ai_select_info.s1 or ""
  check("失步：下一次输入没有模型结果（s1 以 skip 开头）", s1:match("^skip") ~= nil, s1)
  check("失步：模型没有重排，经理仍在首位，只有模糊音扣分改动顺序",
    order[1] == "经理" and order[2] == "精力" and order[3] == "经历", table.concat(order, ","))
  -- 再上屏一个词后上文恢复，重新推理
  hist[#hist + 1] = "经理"
  ctx.input = ""
  key("a")
  check("失步：之后的上屏重新积累上文", _G.ai_rerank_context_get():find("经理", 1, true) ~= nil, tostring(_G.ai_rerank_context_get()))
  ctx.input = ""
end

local function sh(cmd)
  local p = io.popen(cmd .. " 2>&1; echo \"rc=$?\"")
  local s = p:read("a"); p:close()
  return s, tonumber(s:match("rc=(%d+)%s*$"))
end
local q = "'" .. ud .. "'"
local out, rc = sh("python3 -I '" .. repo .. "/tools/replay.py' " .. q .. " --anchor-rate 1 --check")
check("replay.py 用当时配置逐条复现最终首位", rc == 0 and out:find("自检不一致 0 条"), out:match("[^\n]*自检[^\n]*"))
out, rc = sh("python3 -I '" .. repo .. "/tools/replay.py' " .. q .. " --anchor-rate 1 --set fb=0.2")
check("replay.py 全部配置可运行", rc == 0 and out:find("只用模型分") and out:find("自定义"), rc and tostring(rc))
check("输出不含候选文字", not out:find("经理") and not out:find("jingli"))
out, rc = sh("python3 -I '" .. repo .. "/tools/corpus_report.py' " .. q .. " --anchor-rate 1 --rescore '"
  .. so_dir .. "' '" .. model .. "' '" .. vocab .. "'")
check("corpus_report.py 含重新打分可运行", rc == 0 and out:find("只用模型重新打分"), rc and tostring(rc))
check("报告不含候选文字", not out:find("经理") and not out:find("精力"))

if fails > 0 then print(fails .. " FAILED"); os.exit(1) end
print("ALL PASS")
os.exit(0)
