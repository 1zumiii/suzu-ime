-- 按键仿真：不依赖真机和 Rime，用模拟的 Rime 环境驱动真实的 ai_rerank_processor / ai_rerank_filter 与推理核心，
-- 按人类打字节奏（按键间隔 100~300 ms，含快速连按、退格改字、退格与回车清上文、停顿、四字两阶段）统计：
--   跳过率（发给核心的、上文和候选都合格的第一阶段打分请求中没有拿到结果的比例）、跳过原因、
--   每次 score 调用的墙钟时间、每次按键在输入法线程上花掉的总时间（处理器 + 过滤器）。
-- 只输出数字，不写任何含文字的文件。
--
-- 用法：lua keysim.lua <仓库目录> <模块目录> <用例 cases.tsv> <单词数> [选项…]
--   <模块目录>：rime_llm.so 所在目录（进程内核心，或守护进程模式下的客户端库）
--   选项（key=value）：
--     mode=inproc|daemon   inproc 时需要 model=、vocab=；daemon 时需要 rime_dir=（守护进程的 --rime-dir，令牌在里面）
--     model=... vocab=...  进程内模式的模型与词表
--     threads=N            进程内模式的线程数（默认 2）
--     seed=N               随机种子（默认 1）
--     speed=F              节奏倍数：按键间隔乘以 F（默认 1；0.5 = 打字快一倍）
--     out=PATH             另写一份逐次记录（只含数字与原因码），便于画分布
--     chaos=SCRIPT         故障注入：每输入 10 个词在后台执行一次「SCRIPT <词序号>」（由脚本决定杀进程、冻结、删模型等）
--     ranker=… max_candidates=N code_pattern=… gate_margin=F gate_margin_short=F generate=1 gen_k=N gen_beam=N …   覆盖对应的 llm_rerank 配置
--     cn_dicts=DIR         词典目录（含 base.dict.yaml 与 ext.dict.yaml），候选生成用；进程内模式须给
--     label=TEXT           结果行的标签
local repo, so_dir, cases_path, n_words = arg[1], arg[2], arg[3], tonumber(arg[4] or "200")
local opt = { mode = "inproc", threads = "2", seed = "1", speed = "1", label = "" }
for i = 5, #arg do
  local k, v = arg[i]:match("^([%w_]+)=(.*)$")
  if k then opt[k] = v end
end
package.path = repo .. "/rime/lua/?.lua;" .. package.path
package.cpath = so_dir .. "/?.so;" .. package.cpath

-- ---------- 用例：上文与候选 ----------
local CASES = {}
for ln in io.lines(cases_path) do
  local ctx, cs = ln:match("^%d+\t%d+\t([^\t]*)\t(.*)$")
  if ctx then
    local cands = {}
    for c in cs:gmatch("[^|]+") do cands[#cands + 1] = c end
    if #cands >= 2 then CASES[#CASES + 1] = { ctx = ctx, cands = cands } end
  end
end
assert(#CASES > 10, "no cases")

-- 确定性的随机数（不依赖 math.random 的实现）
local rs = tonumber(opt.seed) * 2654435761 % 4294967296
local function rnd() rs = (rs * 1103515245 + 12345) % 2147483648; return rs / 2147483648 end
local function rint(a, b) return a + math.floor(rnd() * (b - a + 1)) end

-- ---------- 推理核心：加载并在调用处计时 ----------
local m = require("rime_llm")
if os.getenv("KEYSIM_PORT") and m.set_enabled then m.port = tonumber(os.getenv("KEYSIM_PORT")) end
if m.set_enabled then m.set_enabled(true) end   -- 守护进程重启后默认关闭，由客户端打开
local now = m.now_ms
assert(now, "module has no now_ms")
local function sleep_ms(ms)
  if ms <= 0 then return end
  if m.sleep_ms then m.sleep_ms(ms) else os.execute(string.format("sleep %.3f", ms / 1000)) end
end

local key_core_ms = 0          -- 本次按键里花在核心调用上的时间
local rec = { score = {}, score_s2 = {}, key = {}, prep = {} }
local cnt = { eligible = 0, ok = 0, skip = 0, reasons = {}, ineligible = 0, s2 = 0, s2_ok = 0, keys = 0 }
cur_word = 0                    -- 当前是第几个词（全局，打分包装里用来统计最后 20 个词的情况）
local tail = { eligible = 0, ok = 0 }
local function utf8len(s) return utf8.len(s) or #s end

local orig = { score = m.score, prepare = m.prepare, is_ready = m.is_ready, touch = m.touch }
local function wrap(name, f)
  return function(...)
    local t0 = now()
    local r = table.pack(f(...))
    local dt = now() - t0
    key_core_ms = key_core_ms + dt
    if name == "prepare" then rec.prep[#rec.prep + 1] = dt end
    return table.unpack(r, 1, r.n)
  end
end
rawset(m, "prepare", wrap("prepare", orig.prepare))
rawset(m, "is_ready", wrap("is_ready", orig.is_ready))
if orig.touch then rawset(m, "touch", wrap("touch", orig.touch)) end
rawset(m, "score", function(ctx, cands, allow_sync)
  local t0 = now()
  local r, why = orig.score(ctx, cands, allow_sync)
  local dt = now() - t0
  key_core_ms = key_core_ms + dt
  local stage2 = allow_sync == nil        -- 过滤器的第二阶段不传第三个参数
  if stage2 then
    cnt.s2 = cnt.s2 + 1
    if type(r) == "table" then cnt.s2_ok = cnt.s2_ok + 1 end
    rec.score_s2[#rec.score_s2 + 1] = dt
  else
    -- 合格：上文至少 2 个字、候选至少 2 个（Suzu 的最低要求）；其余请求核心本来就不打分，不算跳过
    if utf8len(ctx) >= 2 and #cands >= 2 then
      cnt.eligible = cnt.eligible + 1
      rec.score[#rec.score + 1] = dt
      local in_tail = cur_word > n_words - 20
      if in_tail then tail.eligible = tail.eligible + 1 end
      if type(r) == "table" then
        cnt.ok = cnt.ok + 1
        if in_tail then tail.ok = tail.ok + 1 end
      else
        cnt.skip = cnt.skip + 1
        local k = tostring(why or "nil")
        cnt.reasons[k] = (cnt.reasons[k] or 0) + 1
      end
    else
      cnt.ineligible = cnt.ineligible + 1
    end
  end
  return r, why
end)
package.loaded["rime_llm"] = m

-- ---------- 模拟 Rime 环境（与 filter_test.lua 相同的写法）----------
local ud = os.getenv("KEYSIM_UD") or ("/tmp/keysim_ud_" .. tostring(os.time()))
if opt.mode == "daemon" then ud = assert(opt.rime_dir, "daemon mode needs rime_dir=") end
os.execute("mkdir -p '" .. ud .. "/lua'")
if opt.cn_dicts then os.execute("ln -sfn '" .. opt.cn_dicts .. "' '" .. ud .. "/cn_dicts'") end   -- 候选生成读用户目录里的词典
local yielded
function yield(c) yielded[#yielded + 1] = c end
rime_api = { get_user_data_dir = function() return ud end }
local function mk(text, s, e, ctype) return { text = text, _start = s, _end = e, type = ctype or "phrase", comment = "" } end
function Candidate(ctype, s, e, text, comment) local c = mk(text, s, e, ctype); c.comment = comment or ""; return c end
function ShadowCandidate(c, ctype, text, comment) local n = mk(text, c._start, c._end, ctype); n.comment = comment or ""; return n end
local function mk_translation(list)
  local idx = 0
  return { iter = function() return function() idx = idx + 1; return list[idx] end end }
end
local tail_cands = {}
Component = { Translator = function() return { query = function(_, rest) return mk_translation(tail_cands[rest] or {}) end } end }

local conf = { ["llm_rerank/max_candidates"] = 5 }
-- 配置项：ranker=legacy|unified、max_candidates=N、code_pattern=…、gate_margin=F、gate_margin_short=F（按手机的配置对照时用）
for _, k in ipairs({ "ranker", "max_candidates", "code_pattern", "gate_margin", "gate_margin_short", "gen_k", "gen_beam",
                     "max_candidates_short", "short_input_len" }) do
  if opt[k] then conf["llm_rerank/" .. k] = tonumber(opt[k]) or opt[k] end
end
if opt.generate then conf["llm_rerank/generate"] = (opt.generate == "1" or opt.generate == "true") end
if opt.mode == "inproc" then
  conf["llm_rerank/student_model"] = assert(opt.model, "inproc mode needs model=")
  conf["llm_rerank/student_vocab"] = assert(opt.vocab, "inproc mode needs vocab=")
  conf["llm_rerank/model_path"] = opt.model
  conf["llm_rerank/cpu_cores"] = tonumber(opt.threads)
end
local hist = {}
local function mk_env(input)
  local ch = { to_table = function() local t = {}; for i, w in ipairs(hist) do t[i] = { text = w } end; return t end }
  return { engine = {
    schema = { config = {
      get_string = function(_, k) return conf[k] and tostring(conf[k]) or nil end,
      get_int = function(_, k) return type(conf[k]) == "number" and math.floor(conf[k]) or nil end,
      get_double = function(_, k) return type(conf[k]) == "number" and conf[k] or nil end,
      get_bool = function(_, k) return conf[k] end,
    } },
    context = {
      input = input, commit_history = ch,
      composition = { back = function() return {} end },
      get_option = function(_, name) return name == "ai_rerank" or name == "ai_stage2" end,
      set_option = function() end,
      option_update_notifier = { connect = function() end },
      select_notifier = { connect = function() end },
      commit_notifier = { connect = function() end },
    },
  } }
end

local filter = require("ai_rerank_filter")
local processor = require("ai_rerank_processor")
if type(processor) == "table" and processor.init then processor.init(mk_env("")) end

-- 一次按键：处理器 + （有编码时）过滤器，整段计时
local function keystroke(repr, input, cands_list)
  key_core_ms = 0
  local t0 = now()
  local env = mk_env(input)
  processor({ release = function() return false end, repr = function() return repr end }, env)
  if input ~= "" and cands_list then
    yielded = {}
    filter(mk_translation(cands_list), env)
  end
  local dt = now() - t0
  cnt.keys = cnt.keys + 1
  rec.key[#rec.key + 1] = dt
end

-- 按键间隔：大多 100~300 ms；15% 快速连按 40~80 ms
local speed = tonumber(opt.speed)
local function gap() return (rnd() < 0.15 and rint(40, 80) or rint(100, 300)) * speed end

-- ---------- 等模型就绪（与真实使用一致：只是不停地按键）----------
hist = { "今天" }                       -- 有上文时处理器才会发预解码，从而触发加载
local t_start = now()
while not orig.is_ready() do
  keystroke("a", "", nil); sleep_ms(200)
  if now() - t_start > 60000 then print("FAIL: model never ready"); os.exit(1) end
end
keystroke("a", "", nil); sleep_ms(300)

local LETTERS = "abcdefghijklmnopqrstuvwxyz"
local function letter(i) local k = (i * 7) % 26 + 1; return LETTERS:sub(k, k) end
local function cand_objs(cands, e) local t = {}; for i, w in ipairs(cands) do t[i] = mk(w, 0, e) end; return t end

for w = 1, n_words do
  cur_word = w
  if opt.chaos and w % 10 == 0 then os.execute(opt.chaos .. " " .. w .. " >/dev/null 2>&1 &") end
  local case = CASES[rint(1, #CASES)]
  local other = CASES[rint(1, #CASES)]
  local four = rnd() < 0.15
  local tail = CASES[rint(1, #CASES)]
  local l1 = rint(4, 9)
  local l2 = four and rint(4, 8) or 0
  local total = l1 + l2
  local input = ""
  local i = 0
  while #input < total do
    i = i + 1
    sleep_ms(gap())
    -- 退格改字：输入中途 8% 的概率删掉一个字母（随后会再补回来）
    if #input >= 3 and rnd() < 0.08 then
      input = input:sub(1, -2)
    else
      input = input .. letter(w + i)
    end
    local cl
    if #input < 3 then cl = cand_objs(other.cands, #input)
    elseif #input < l1 then cl = cand_objs(other.cands, #input)          -- 半截拼音：候选还会变
    elseif #input <= l1 then cl = cand_objs(case.cands, #input)
    else
      -- 四字：第一段候选只覆盖前 l1 个字母，原首位是 Rime 造的整句
      local t = { mk(case.cands[1] .. tail.cands[1], 0, #input, "sentence") }
      for _, c in ipairs(cand_objs(case.cands, l1)) do t[#t + 1] = c end
      tail_cands[input:sub(l1 + 1)] = cand_objs(tail.cands, #input - l1)
      cl = t
    end
    keystroke(letter(w + i), input, cl)
  end
  -- 选词上屏（空格），随后下一次按键里处理器同步 commit_history
  sleep_ms(gap())
  keystroke("space", input, nil)
  hist[#hist + 1] = case.cands[rint(1, math.min(2, #case.cands))] .. (four and tail.cands[1] or "")
  local r = rnd()
  if r < 0.10 then                       -- 标点
    sleep_ms(gap()); hist[#hist + 1] = (rnd() < 0.5) and "，" or "。"; keystroke("comma", "", nil)
  elseif r < 0.18 then                   -- 退格（光标前删字）：上文清空
    for _ = 1, rint(1, 3) do sleep_ms(gap()); keystroke("BackSpace", "", nil) end
  elseif r < 0.23 then                   -- 回车换行：上文清空
    sleep_ms(gap()); keystroke("Return", "", nil)
  end
  if rnd() < 0.10 then sleep_ms(rint(500, 2000) * speed) end   -- 停顿想下一句
  if #hist > 40 then local nh = {}; for k = #hist - 20, #hist do nh[#nh + 1] = hist[k] end; hist = nh end
end

-- ---------- 汇总 ----------
local function pct(t, p)
  if #t == 0 then return -1 end
  local s = {}; for k, v in ipairs(t) do s[k] = v end
  table.sort(s)
  return s[math.max(1, math.ceil(#s * p))]
end
local reasons = {}
for k, v in pairs(cnt.reasons) do reasons[#reasons + 1] = k .. "=" .. v end
table.sort(reasons)
local function over(t, x) local n = 0; for _, v in ipairs(t) do if v > x then n = n + 1 end end; return n end
print(string.format("%s eligible=%d ok=%d skip=%d (%.2f%%) reasons[%s] ineligible=%d stage2=%d/%d last20words_ok=%d/%d",
  opt.label, cnt.eligible, cnt.ok, cnt.skip, cnt.eligible > 0 and 100 * cnt.skip / cnt.eligible or 0,
  table.concat(reasons, ","), cnt.ineligible, cnt.s2_ok, cnt.s2, tail.ok, tail.eligible))
print(string.format("%s score1 ms: p50=%.1f p90=%.1f p99=%.1f max=%.1f n=%d | stage2 ms: p50=%.1f p99=%.1f max=%.1f n=%d",
  opt.label, pct(rec.score, .5), pct(rec.score, .9), pct(rec.score, .99), pct(rec.score, 1), #rec.score,
  pct(rec.score_s2, .5), pct(rec.score_s2, .99), pct(rec.score_s2, 1), #rec.score_s2))
print(string.format("%s key ms: p50=%.1f p90=%.1f p99=%.1f max=%.1f n=%d >50ms=%d >100ms=%d | prepare ms p99=%.1f max=%.1f",
  opt.label, pct(rec.key, .5), pct(rec.key, .9), pct(rec.key, .99), pct(rec.key, 1), #rec.key, over(rec.key, 50), over(rec.key, 100),
  pct(rec.prep, .99), pct(rec.prep, 1)))
if m.status then
  local ok, st = pcall(m.status)
  if ok and st then print(opt.label .. " status " .. tostring(st)) end
end
if opt.out then
  local f = io.open(opt.out, "w")
  for _, v in ipairs(rec.score) do f:write("s1\t", string.format("%.2f", v), "\n") end
  for _, v in ipairs(rec.score_s2) do f:write("s2\t", string.format("%.2f", v), "\n") end
  for _, v in ipairs(rec.key) do f:write("key\t", string.format("%.2f", v), "\n") end
  f:close()
end
if opt.mode == "inproc" and not os.getenv("KEYSIM_UD") then os.execute("rm -rf '" .. ud .. "'") end
os.exit(0)
