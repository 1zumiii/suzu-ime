-- 用模拟的 Rime 环境 + 真实 rime_llm.so + 真实模型，端到端跑 ai_rerank_filter / ai_rerank_processor
-- 用法：lua filter_test.lua <仓库目录> <用户目录> <suzu.gguf>
--   用户目录里须有 lua/rime_llm.so、suzu.gguf、suzu.vocab.txt（与桌面端的安装布局一致，处理器按这两个文件名启用 Suzu）
local repo, ud, model = arg[1], arg[2], arg[3]
package.path = repo .. "/rime/lua/?.lua;" .. package.path

local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end

-- ---------- 模拟 Rime 环境 ----------
local yielded = nil
function yield(c) yielded[#yielded + 1] = c end
rime_api = { get_user_data_dir = function() return ud end }

local function mk(text, s, e, ctype, comment)
  return { text = text, _start = s, _end = e, type = ctype or "phrase", comment = comment or "" }
end
function Candidate(ctype, s, e, text, comment) return mk(text, s, e, ctype, comment) end
function ShadowCandidate(c, ctype, text, comment, _)
  local n = mk(text, c._start, c._end, ctype, comment); n.shadow = true; return n
end

local function mk_translation(list)
  local idx = 0   -- 共享游标：中断后再调用 iter() 会接着取
  return { iter = function() return function() idx = idx + 1; return list[idx] end end }, function() return idx end
end

local tail_cands = {}
Component = { Translator = function(_, _, name)
  return { query = function(_, rest, _)
    local t = mk_translation(tail_cands[rest] or {})
    return t
  end }
end }

local conf = { ["llm_rerank/model_path"] = model, ["llm_rerank/max_candidates"] = 5 }
local function mk_env(input, hist, enabled, extra_conf)
  local c = {}
  for k, v in pairs(conf) do c[k] = v end
  for k, v in pairs(extra_conf or {}) do c[k] = v end
  local ch = { to_table = function() local t = {}; for i, w in ipairs(hist) do t[i] = { text = w } end; return t end }
  return {
    engine = {
      schema = { config = {
        get_string = function(_, k) return c[k] and tostring(c[k]) or nil end,
        get_int = function(_, k) return type(c[k]) == "number" and math.floor(c[k]) or nil end,
        get_double = function(_, k) return type(c[k]) == "number" and c[k] or nil end,
        get_bool = function(_, k) return c[k] end,
      } },
      context = {
        input = input,
        commit_history = ch,
        composition = { back = function() return {} end },
        get_option = function(_, name) return (name == "ai_rerank" and enabled) or name == "ai_stage2" end,
      },
    },
  }
end

-- ---------- 加载（processor 负责加载 .so）----------
os.execute("mkdir -p '" .. ud .. "/lua'")
local filter = require("ai_rerank_filter")
local processor = require("ai_rerank_processor")

local settle = true
local function press(env, repr)
  local r = processor({ release = function() return false end, repr = function() return repr end }, env)
  if settle then os.execute("sleep 0.1") end   -- 等后台预解码完成（Suzu 解码一段上文只要几毫秒）
  return r
end

-- 提交历史 → processor 同步 → 上文
local hist = { "他在公司当" }
local env0 = mk_env("", hist, true)
check("processor returns noop on normal key", press(env0, "a") == 2)
check("llm module loaded by processor", _G.ai_rerank_llm ~= nil)
check("context built from commit_history", _G.ai_rerank_context_get() == "他在公司当", _G.ai_rerank_context_get())

-- 等模型就绪（prepare/score 懒加载）
local llm = _G.ai_rerank_llm
-- 功能检查用宽松的时限，机器忙（例如与其他测试并行）时也不会因为超时而“没重排”；
-- 时限本身由 I 组单独检查（I 组临时改回真实的默认值）
local REAL_BUDGET = llm.budget_ms or 40
llm.budget_ms = 2000
llm.sync_budget_ms = 2000
local t0 = os.time()
while not llm.is_ready() do
  local _, why = llm.score("你好", { "世界", "是借" })
  if why == "no_model" then print("FAIL: model failed to load (see rime_llm_log.txt)"); os.exit(1) end
  os.execute("sleep 0.3")
  if os.time() - t0 > 60 then print("FAIL model never ready"); os.exit(1) end
end
os.execute("sleep 0.5")

local function run(env, cands)
  yielded = {}
  local tr = mk_translation(cands)
  filter(tr, env)
  local texts = {}
  for i, c in ipairs(yielded) do texts[i] = c.text end
  return texts, yielded
end

-- A: 开关关闭 → 原样、惰性透传
do
  local cands = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6) }
  local texts = run(mk_env("jingli", hist, false), cands)
  check("A. disabled: passthrough unchanged", table.concat(texts, ",") == "经理,经历,精力", table.concat(texts, ","))
end

-- B: 两音节同音词，上文 "我最近没有太多" → 精力 应排第一
do
  hist = { "我最近没有太多" }
  press(mk_env("", hist, true), "b")   -- 同步新上文
  local cands = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6), mk("警力", 0, 6) }
  local texts, ys = run(mk_env("jingli", hist, true), cands)
  check("B. two-syllable rerank: 精力 first", texts[1] == "精力", table.concat(texts, ","))
  check("B. all 5 candidates kept", #texts == 5)
  check("B. badge on first", ys[1].comment:find("◇", 1, true) ~= nil, ys[1].comment)
end

-- B2: 置信度门控与纠错记忆
do
  local cfgref = _G.ai_rerank_cfg
  local cands = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6), mk("警力", 0, 6) }
  cfgref.gate_margin = 100
  local texts = run(mk_env("jingli", hist, true), cands)
  check("B2. 门控很严时保持 Rime 原顺序", texts[1] == "经理", table.concat(texts, ","))
  local pp = require("personal_pairs")
  pp._reset_all(); pp.init(ud .. "/recent_pairs.tsv")
  pp.record("他在公司当", os.time())
  pp.record_choice("他在公司当", "jingli", "精力", 2.0)
  texts = run(mk_env("jingli", hist, true), cands)
  check("B2. 纠错记忆命中的候选不受门控限制", texts[1] == "精力", table.concat(texts, ","))
  cfgref.gate_margin = 0
  pp._reset_all()
end

-- B3: emoji、英文等不含汉字的候选不参与重排，原位置不动
do
  hist = { "我最近没有太多" }
  press(mk_env("", hist, true), "m")
  _G.llm_filter_cache = nil
  local cands = { mk("经理", 0, 6), mk("😀", 0, 6, "simplified"), mk("经历", 0, 6), mk("精力", 0, 6),
                  mk("wifi", 0, 6, "user_phrase"), mk("敬礼", 0, 6), mk("🙂", 0, 6, "emoji") }
  local texts = run(mk_env("jingli", hist, true), cands)
  check("B3. emoji and english kept at original positions", texts[2] == "😀" and texts[5] == "wifi" and texts[7] == "🙂", table.concat(texts, ","))
  check("B3. text candidates still reranked: 精力 first", texts[1] == "精力", table.concat(texts, ","))
  check("B3. no candidate lost", #texts == 7, tostring(#texts))
  -- 含汉字的中英混合词仍然参与重排
  _G.llm_filter_cache = nil
  local mixed = { mk("经理", 0, 6), mk("A经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6) }
  local t2 = run(mk_env("jingli", hist, true), mixed)
  local seen_mixed = false
  for i, x in ipairs(t2) do if x == "A经历" then seen_mixed = true end end
  check("B3. mixed han+latin candidate still takes part", seen_mixed and #t2 == 4 and t2[1] == "精力", table.concat(t2, ","))
end

-- B5: 怪词：标记后排到同一组候选的最后，取消标记后恢复
do
  hist = { "我最近没有太多" }
  press(mk_env("", hist, true), "w")
  local path = ud .. "/weird_words.tsv"
  local function write_weird(lines) local f = io.open(path, "w"); f:write(lines); f:close(); _G.ai_rerank_weird_reload() end
  local function cands5() return { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6), mk("警力", 0, 6) } end
  _G.llm_filter_cache = nil
  local base = run(mk_env("jingli", hist, true), cands5())
  check("B5. 未标记：精力排第一", base[1] == "精力", table.concat(base, ","))
  write_weird("精力\t1700000000\n")
  _G.llm_filter_cache = nil
  local marked = run(mk_env("jingli", hist, true), cands5())
  check("B5. 标记后怪词排到最后", marked[#marked] == "精力" and marked[1] ~= "精力", table.concat(marked, ","))
  check("B5. 标记后候选不丢失", #marked == 5, tostring(#marked))
  write_weird("")
  _G.llm_filter_cache = nil
  local restored = run(mk_env("jingli", hist, true), cands5())
  check("B5. 取消标记后恢复", restored[1] == "精力", table.concat(restored, ","))
  os.remove(path)
  _G.ai_rerank_weird_reload()
end

-- B4: 拼音贴合度：模糊音凑出来的候选被识别，精确输入、简拼、非拼音注释不扣分
do
  local f = _G.ai_rerank_is_fuzzy
  local function fz(input, comment, s, e) return f({ comment = comment, _start = s or 0, _end = e or #input }, input) end
  check("B4. 精确输入不是模糊", fz("zhongguo", "zhong guo") == false)
  check("B4. zh→z 模糊被识别", fz("zhongguo", "zong guo") == true)
  check("B4. c→ch 模糊被识别", fz("buxiangci", "bu xiang chi") == true)
  check("B4. 精确 ci 不是模糊", fz("buxiangci", "bu xiang ci") == false)
  check("B4. ang→an 模糊被识别", fz("buxiangci", "bu xian ci") == true)
  check("B4. 简拼不是模糊", fz("zg", "zhong guo") == false)
  check("B4. 简拼加全拼混合不是模糊", fz("zhonggu", "zhong guo") == false)
  check("B4. 非拼音注释不扣分", fz("zhongguo", "◇") == false)
  check("B4. 空注释不扣分", fz("zhongguo", "") == false)
  check("B4. 带全角方括号的真实注释格式", fz("buxiangci", "［bu xiang chi］") == true and fz("buxiangci", "［bu xiang ci］") == false)
  check("B4. 带声调的注释", fz("zhongguo", "zhōng guó") == false and fz("zhongguo", "zōng guó") == true)
  check("B4. ü 与 v、u 等价", fz("nvren", "nǚ rén") == false)
  check("B4. 前缀词只比较覆盖的那段", fz("zhongguoren", "zhong guo", 0, 8) == false)
end

-- B4b: comment 为空的候选（用户词典来源）退回 ReverseDb 取单字读音：shang 下的“三”“删”是模糊音，“上”不是
do
  local readings = { ["三"] = "san", ["上"] = "shang", ["删"] = "shan", ["长"] = "chang zhang", ["怎"] = "zen", ["么"] = "me", ["回"] = "hui", ["事"] = "shi" }
  _G.ReverseDb = function() return { lookup = function(_, ch) return readings[ch] end } end
  _G.ai_rerank_reset_reverse()
  local f = _G.ai_rerank_is_fuzzy
  local function fz(input, text, s, e) return f({ text = text, comment = "", _start = s or 0, _end = e or #input }, input) end
  check("B4b. 空注释：精确读音不扣分", fz("shang", "上") == false)
  check("B4b. 空注释：sh→s、ang→an 凑出来的被识别", fz("shang", "三") == true)
  check("B4b. 空注释：ang→an 被识别", fz("shang", "删") == true)
  check("B4b. 空注释：多音字任一读音对得上就不扣分", fz("zhang", "长") == false and fz("chang", "长") == false)
  check("B4b. 空注释：简拼不扣分", fz("s", "三") == false)
  check("B4b. 空注释：多字词逐字对齐", fz("zenmehuishi", "怎么回事") == false and fz("zenmehuisi", "怎么回事") == true)
  check("B4b. 空注释：查不到读音不扣分", fz("shang", "𦜳") == false)
  _G.ReverseDb = nil
  _G.ai_rerank_reset_reverse()
  check("B4b. 没有 ReverseDb 时不扣分", fz("shang", "三") == false)
end

-- B6: 简拼放大候选数：2 个字母、打分组首位候选字数等于字母数时，打分的候选数取 max_candidates_short
do
  hist = { "他在公司当" }
  press(mk_env("", hist, true), "d")
  local words = { "经理", "经历", "精力", "敬礼", "警力", "经里", "镜里", "惊立" }
  local function scored(input, first_len, conf)
    _G.llm_filter_cache = nil
    package.loaded["ai_rerank_filter"] = nil      -- 过滤器的配置只读一次：每组配置重新加载
    filter = require("ai_rerank_filter")
    local cands = {}
    for i, w in ipairs(words) do cands[i] = mk(i == 1 and first_len == 1 and "经" or w, 0, #input) end
    run(mk_env(input, hist, true, conf), cands)
    local n = 0
    for _ in pairs((_G.llm_filter_cache and _G.llm_filter_cache.scores) or {}) do n = n + 1 end
    return n
  end
  local base = { ["llm_rerank/max_candidates"] = 3, ["llm_rerank/code_pattern"] = "[a-z]{2,}" }
  local wide = { ["llm_rerank/max_candidates"] = 3, ["llm_rerank/code_pattern"] = "[a-z]{2,}",
                 ["llm_rerank/max_candidates_short"] = 6, ["llm_rerank/short_input_len"] = 3 }
  check("B6. 未配置放大：只打分 max_candidates 个", scored("jl", 2, base) == 3)
  check("B6. 简拼（首位两字、两个字母）：放大到 6 个", scored("jl", 2, wide) == 6)
  check("B6. 首位只有一个字（不是简拼）：不放大", scored("jl", 1, wide) == 3)
  check("B6. 字母数超过 short_input_len：不放大", scored("jlgl", 2, wide) == 3)
  -- 恢复默认配置，不影响后面的测试
  package.loaded["ai_rerank_filter"] = nil
  filter = require("ai_rerank_filter")
  run(mk_env("zzz", hist, true), { mk("甲", 0, 3), mk("乙", 0, 3) })
  _G.llm_filter_cache = nil
end

-- C: 上文为空 → 不重排（原样）
do
  hist = {}
  press(mk_env("", hist, true), "e")
  local cands = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6) }
  local texts = run(mk_env("jingli", hist, true), cands)
  check("C. empty context: unchanged order", table.concat(texts, ",") == "经理,经历,精力", table.concat(texts, ","))
end

-- D: 四字两阶段：上文 "他在公司当"，jingliguolai → 经理过来
do
  hist = { "他在公司当" }
  press(mk_env("", hist, true), "c")
  _G.llm_filter_cache = nil
  tail_cands["guolai"] = { mk("过来", 0, 6), mk("果来", 0, 6), mk("国来", 0, 6) }
  local cands = {
    mk("经历过来", 0, 12, "sentence"),
    mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6),
    mk("敬礼", 0, 6),
  }
  local texts, ys = run(mk_env("jingliguolai", hist, true), cands)
  check("D. two-stage: 经理过来 first", texts[1] == "经理过来", table.concat(texts, ","))
  check("D. two-stage badge", ys[1].comment == "◈", ys[1].comment)
  check("D. original sentence kept", table.concat(texts, ","):find("经历过来", 1, true) ~= nil)
end

-- D2: 原首位是覆盖整串的词典词时，不拼接二阶段候选（boruoxiang → 薄弱项，不能被“波若想”抢走）
do
  hist = { "你先给隔壁来个针对" }
  press(mk_env("", hist, true), "n")
  _G.llm_filter_cache = nil
  tail_cands["xiang"] = { mk("想", 0, 5), mk("项", 0, 5), mk("向", 0, 5) }
  local cands = {
    mk("薄弱项", 0, 10, "user_phrase"),
    mk("波若", 0, 5), mk("薄弱", 0, 5), mk("博若", 0, 5), mk("伯若", 0, 5),
  }
  local texts, ys = run(mk_env("boruoxiang", hist, true), cands)
  local has_s2 = false
  for _, y in ipairs(ys) do if y.comment == "◈" then has_s2 = true end end
  check("D2. native full-cover word: no two-stage candidate", not has_s2, table.concat(texts, ","))
  check("D2. native full-cover word stays first", texts[1] == "薄弱项", table.concat(texts, ","))
end

-- E: scan_limit 之后的候选惰性透传、顺序不变
do
  hist = { "他在公司当" }
  press(mk_env("", hist, true), "d")
  _G.llm_filter_cache = nil
  local cands = {}
  for i = 1, 12 do cands[i] = mk("词" .. i, 0, 6) end
  -- 过滤器的配置只读一次：重新加载模块，让 scan_limit 生效
  package.loaded["ai_rerank_filter"] = nil
  filter = require("ai_rerank_filter")
  local texts = run(mk_env("jingli", hist, true, { ["llm_rerank/scan_limit"] = 3 }), cands)
  check("E. no candidate lost with scan_limit", #texts == 12, tostring(#texts))
  local tail_ok = true
  for i = 4, 12 do if texts[i] ~= "词" .. i then tail_ok = false end end
  check("E. candidates after scan_limit keep order", tail_ok, table.concat(texts, ","))
end


-- I: 预解码没就绪时不卡、不阻塞（Suzu）：上文解码只要几毫秒，核心在时限内现场解码并重排；
--    时限内算不完就保持原顺序立即返回，算完的结果进缓存，下一次按键直接用上。用墙钟计时（os.clock 会把推理线程的 CPU 也算进来）。
do
  local now = _G.ai_rerank_llm.now_ms
  hist = { "我最近没有太多" }
  settle = false
  press(mk_env("", hist, true), "i1")          -- 上文变了，后台预解码刚开始
  _G.llm_filter_cache = nil
  local cands = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6), mk("警力", 0, 6) }
  local budget = REAL_BUDGET
  _G.ai_rerank_llm.budget_ms = budget
  local t0 = now()
  local texts = run(mk_env("jingli", hist, true), cands)
  local dt = now() - t0
  check("I. not prepared yet: no candidate lost", #texts == 5, tostring(#texts))
  check("I. not prepared yet: returns within the time limit", dt < budget + 15, string.format("%.1f ms (limit %d ms)", dt, budget))
  check("I. not prepared yet: reranked within the limit (精力 first)", texts[1] == "精力", table.concat(texts, ","))
  -- 时限极短：这次保持原顺序且立即返回；任务在后台算完进缓存，下一次按键重排
  hist = { "这段时间我实在没有太多" }
  press(mk_env("", hist, true), "i2")
  _G.llm_filter_cache = nil
  _G.ai_rerank_llm.budget_ms = 0.01
  t0 = now()
  texts = run(mk_env("jingli", hist, true), cands)
  dt = now() - t0
  -- Windows: the MSVC condition variable rounds a timeout up to >= 1 ms (and the system timer ticks at 1 to 15 ms), so a 0.01 ms limit cannot
  -- be reached; the background pre-decode (a few ms) has finished by the time the wait returns and the finished result is returned. Both outcomes
  -- are correct there; what must hold on every platform is that the call returns quickly (next check) and that no candidate is lost.
  local kept = table.concat(texts, ",") == "经理,经历,精力,敬礼,警力"
  local reranked_in_time = package.config:sub(1, 1) == "\\" and texts[1] == "精力" and #texts == 5
  check("I. tiny limit: order kept" .. (package.config:sub(1, 1) == "\\" and " (or reranked within the 1 ms timer floor on Windows)" or ""), kept or reranked_in_time, table.concat(texts, ","))
  check("I. tiny limit: skipped call returns fast (<10 ms wall)", dt < 10, string.format("%.1f ms", dt))
  _G.ai_rerank_llm.budget_ms = 2000
  settle = true
  os.execute("sleep 0.3")
  texts = run(mk_env("jingli", hist, true), cands)   -- 再按一次键：结果已在缓存里
  check("I. self-heals within a keystroke: reranked", texts[1] == "精力", table.concat(texts, ","))
end

-- J: 第一阶段按「上文+候选」缓存：输入第二个词的每个字母不重新推理
do
  hist = { "他在公司当" }
  press(mk_env("", hist, true), "j1")
  _G.llm_filter_cache = nil
  _G.ai_rerank_state = nil
  tail_cands["guolai"] = { mk("过来", 0, 6), mk("果来", 0, 6), mk("国来", 0, 6) }
  tail_cands["guola"] = { mk("过拉", 0, 5), mk("国拉", 0, 5) }
  tail_cands["guo"] = { mk("过", 0, 3), mk("国", 0, 3) }
  local first = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6) }
  local function with_sentence(inp, n) local t = { mk("经历过来", 0, n, "sentence") }; for _, c in ipairs(first) do t[#t + 1] = c end; return t end
  local n0 = _G.llm_infer_n or 0
  local texts, ys = run(mk_env("jingliguolai", hist, true), with_sentence("jingliguolai", 12))
  local n1 = _G.llm_infer_n or 0
  check("J. full four-char input: two-stage ran (◈ first)", ys[1].comment == "◈" and texts[1] == "经理过来", table.concat(texts, ","))
  check("J. first call used stage1 + stage2 inference (2)", n1 - n0 == 2, tostring(n1 - n0))

  -- 退格一位：输入变短 → 跳过第二阶段；第一阶段候选没变 → 命中缓存，不再推理
  local n2 = _G.llm_infer_n or 0
  texts, ys = run(mk_env("jingliguola", hist, true), with_sentence("jingliguola", 11))
  check("J. backspace: no two-stage candidate", ys[1].comment ~= "◈" and texts[1] ~= "经理过拉", table.concat(texts, ","))
  check("J. backspace: no new inference (stage1 cache hit)", (_G.llm_infer_n or 0) == n2, tostring((_G.llm_infer_n or 0) - n2))
  -- 同一个输入串被再次调用（翻页等）：仍然保持"改字中"
  texts, ys = run(mk_env("jingliguola", hist, true), with_sentence("jingliguola", 11))
  check("J. same shrunk input again: still skipped", ys[1].comment ~= "◈", table.concat(texts, ","))
  -- 再往后打（变长）：恢复第二阶段
  texts, ys = run(mk_env("jingliguolai", hist, true), with_sentence("jingliguolai", 12))
  check("J. typing forward again: two-stage resumes", ys[1].comment == "◈", table.concat(texts, ","))

  -- 剩余拼音太短（"guo" 只有 3 个字母 < 4）不跑第二阶段
  _G.ai_rerank_state = nil
  texts, ys = run(mk_env("jingliguo", hist, true), with_sentence("jingliguo", 9))
  check("J. short rest (<4 letters): no two-stage", ys[1].comment ~= "◈", table.concat(texts, ","))
end


-- K: 模型被空闲卸载之后，正常打字能自动恢复重排（处理器触发重新加载与预解码）
do
  hist = { "我最近没有太多" }
  _G.llm_filter_cache = nil
  _G.ai_rerank_llm.idle_unload_sec = 1
  _G.ai_rerank_llm.score("你好", { "世界", "是借" })     -- 刷新“最近使用”时间
  os.execute("sleep 2.2")
  check("K. model unloaded after idle", _G.ai_rerank_llm.is_ready() == false)
  local cands = { mk("经理", 0, 6), mk("经历", 0, 6), mk("精力", 0, 6), mk("敬礼", 0, 6), mk("警力", 0, 6) }
  local texts = run(mk_env("jingli", hist, true), cands)
  check("K. while unloaded: order kept, no blocking", table.concat(texts, ",") == "经理,经历,精力,敬礼,警力", table.concat(texts, ","))
  -- 接下来每次按键处理器都会触发加载 → 加载完成后补预解码；模拟几次按键
  for i = 1, 6 do press(mk_env("", hist, true), "k" .. i) end
  -- 等到重新加载完成（不用固定时长：机器忙时加载会慢一些），再按一次键补上预解码
  local t_wait = os.time()
  while not _G.ai_rerank_llm.is_ready() and os.time() - t_wait < 10 do press(mk_env("", hist, true), "kw") end
  press(mk_env("", hist, true), "k7")
  texts = run(mk_env("jingli", hist, true), cands)
  check("K. after reload + prepare: reranking resumes", texts[1] == "精力", table.concat(texts, ","))
  _G.ai_rerank_llm.idle_unload_sec = 0
end

-- F: 单字母输入不触发（默认 code_pattern [a-z]{3,}）
do
  local cands = { mk("的", 0, 1), mk("地", 0, 1), mk("得", 0, 1) }
  local texts = run(mk_env("d", hist, true), cands)
  check("F. single letter not reranked (pins safe)", table.concat(texts, ",") == "的,地,得", table.concat(texts, ","))
end

-- G: 编辑键重置上文
do
  hist = { "他在公司当" }
  press(mk_env("", hist, true), "x")
  check("G. context present before edit key", _G.ai_rerank_context_get() == "他在公司当")
  local r = press(mk_env("", hist, true), "BackSpace")
  check("G. edit key returns 0 (rejected, passes on)", r == 0)
  check("G. context cleared after edit key", _G.ai_rerank_context_get() == "", _G.ai_rerank_context_get())
end

-- H: 不写任何明文文件
do
  local p = io.popen("ls '" .. ud .. "' 2>/dev/null"); local ls = p:read("*a"); p:close()
  local bad = {}
  for name in ls:gmatch("[^\n]+") do
    -- recent_choices.tsv 是本测试里直接调用纠错记忆接口（B2）写的，不是重排模块写的；
    -- suzu.gguf、suzu.vocab.txt 是用户放进来的模型文件（桌面端按这两个文件名自动启用 Suzu）
    local allowed = { lua = true, ["rime_llm_log.txt"] = true, ["recent_choices.tsv"] = true,
                      ["suzu.gguf"] = true, ["suzu.vocab.txt"] = true }
    if not allowed[name] then bad[#bad + 1] = name end
  end
  check("H. only expected files in user dir", #bad == 0, table.concat(bad, ","))
  local f = io.open(ud .. "/rime_llm_log.txt"); local log = f and f:read("*a") or ""; if f then f:close() end
  local leaked = false
  for _, w in ipairs({ "公司", "最近", "经理", "精力", "jingli" }) do if log:find(w, 1, true) then leaked = true end end
  check("H. log contains no input/candidate text", not leaked)
end

print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
os.exit(fails == 0 and 0 or 1)
