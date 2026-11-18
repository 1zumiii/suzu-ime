-- 候选生成（m.generate）的测试：用一份小词典检查切分、适用范围、词频名次、词表外字，以及束宽足够大时与逐个打分一致。
-- 用法：lua gen_test.lua <rime_llm.so 所在目录> <suzu.gguf> <suzu.vocab.txt> <临时目录>
local so_dir, model, vocab, tmp = arg[1], arg[2], arg[3], arg[4]
package.cpath = so_dir .. "/?.so;" .. package.cpath
local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end
os.execute("rm -rf '" .. tmp .. "' && mkdir -p '" .. tmp .. "/cn_dicts'")
-- 格式同雾凇 cn_dicts：YAML 头以 ... 结束，之后每行 词<Tab>拼音<Tab>词频；ext 词典里放一个多音的重复词
local f = io.open(tmp .. "/cn_dicts/base.dict.yaml", "w")
f:write("---\nname: base\n...\n# 注释行\n",
        "经理\tjing li\t100\n精力\tjing li\t90\n经历\tjing li\t80\n教练\tjiao lian\t50\n家里\tjia li\t40\n局里\tju li\t10\n",
        "看电影\tkan dian ying\t70\n快递员\tkuai di yuan\t60\n肯定要\tken ding yao\t20\n",
        "𰻞面\tbiang mian\t500\n",       -- 含词表外字：不收
        "怎么\tzen me\t300\n这么\tzhe me\t200\n知名\tzhi ming\t30\n")
f:close()
f = io.open(tmp .. "/cn_dicts/ext.dict.yaml", "w")
f:write("---\nname: ext\n...\n经理\tjing li\t5\n")
f:close()

local m = require("rime_llm")
m.log_dir, m.model_path, m.student_vocab = tmp, model, vocab
m.sync_budget_ms = 0
local CTX = "他在公司当"

-- 1) 没有词典：no_lexicon；打分照常
local t0 = os.time()
while not m.is_ready() do m.prepare(CTX); m.sleep_ms(50); if os.time() - t0 > 60 then print("FAIL: model never ready"); os.exit(1) end end
local r, why = m.generate(CTX, "jl", 5, true)
check("没有配置词典：no_lexicon", r == nil and why == "no_lexicon", tostring(why))

-- 2) 配置词典（会重载模型；词典在模型就绪后的后台线程里加载）
m.lexicon_dir, m.gen_beam = tmp .. "/cn_dicts", 50
t0 = os.time()
repeat
  m.prepare(CTX); m.sleep_ms(50)
  r, why = m.generate(CTX, "jl", 10, true)
until r or os.time() - t0 > 60
check("配置词典后可以生成（束宽 50，序列池 100 条也能建上下文）", r ~= nil, tostring(why))
r = r or {}
local words = {}
for i, x in ipairs(r) do words[i] = x.w end
check("简拼 jl：只返回首字母匹配的两字词", #r == 6 and not table.concat(words, "|"):find("看电影"), table.concat(words, "|"))
local rank_of = {}
for _, x in ipairs(r) do rank_of[x.w] = x.r end
check("词频名次：经理 1、精力 2、经历 3、教练 4、家里 5、局里 6（多音重复词只算一次）",
  rank_of["经理"] == 1 and rank_of["精力"] == 2 and rank_of["经历"] == 3 and rank_of["教练"] == 4 and rank_of["家里"] == 5
  and rank_of["局里"] == 6)
local desc = true
for i = 2, #r do if r[i].s > r[i - 1].s then desc = false end end
check("结果按模型分降序", desc)

-- 3) 束宽足够大时与逐个打分一致
local sc_r = m.score(CTX, words, true)
local sc = m.get_scores()
local same = sc_r ~= nil
for i, x in ipairs(r) do
  if not sc or math.abs((sc[x.w] or -1e9) - x.s) > 1e-3 or sc_r[i] ~= x.w then same = false end
end
check("与 score 的分数和顺序一致", same)

-- 4) 适用范围与切分
r, why = m.generate(CTX, "jingli", 5, true)
check("全拼 jingli：not_applicable", r == nil and why == "not_applicable", tostring(why))
r, why = m.generate(CTX, "jing'li", 5, true)
check("带隔音符的全拼：not_applicable", r == nil and why == "not_applicable", tostring(why))
r = m.generate(CTX, "jingl", 5, true)
local ws = {}
for _, x in ipairs(r or {}) do ws[#ws + 1] = x.w end
check("混合输入 jingl：只返回首字读 jing 的词", r and #r == 3 and not table.concat(ws):find("教练"), table.concat(ws, "|"))
r = m.generate(CTX, "j'l", 5, true)
check("带隔音符的简拼 j'l（k = 5）", r and #r == 5)
r = m.generate("我们一起去", "kdy", 5, true)
ws = {}
for _, x in ipairs(r or {}) do ws[#ws + 1] = x.w end
check("三字简拼 kdy：看电影、快递员、肯定要", r and #r == 3, table.concat(ws, "|"))
r = m.generate("我们一起去", "kandy", 5, true)
check("三字混合输入 kandy：只有看电影", r and #r == 1 and r[1].w == "看电影")
r = m.generate(CTX, "zm", 5, true)
ws = {}
for _, x in ipairs(r or {}) do ws[#ws + 1] = x.w end
check("z 同时匹配 z 与 zh：怎么、这么、知名", r and #r == 3, table.concat(ws, "|"))
r = m.generate(CTX, "bm", 5, true)
check("含词表外字的词不收：bm 无可生成的词", r == nil)
r, why = m.generate(CTX, "jlkdy", 5, true)
check("超过 3 个字：not_applicable", r == nil and why == "not_applicable", tostring(why))
r, why = m.generate("我", "jl", 5, true)
check("上文不足 2 个字：no_context", r == nil and why == "no_context", tostring(why))
local st = m.status()
check("状态里有生成的计数", st:find('"gen":{"requests":', 1, true) ~= nil)

-- 词频查询：多音重复词取最大权重；单字、不在词典里的词、含词表外字的词返回 nil
check("word_freq 取最大权重", m.word_freq("经理") == 100, tostring(m.word_freq("经理")))
check("word_freq 三字词", m.word_freq("看电影") == 70, tostring(m.word_freq("看电影")))
check("word_freq 单字与未收录词为 nil", m.word_freq("我") == nil and m.word_freq("不存在") == nil)

-- extra.dict.yaml（本项目自己的补充词条）和 base、ext 一样被读，权重按重复词取最大
f = io.open(tmp .. "/cn_dicts/extra.dict.yaml", "w")
f:write("---\nname: extra\n...\n家里\tjia li\t999\n")
f:close()
m.lexicon_dir = tmp .. "/cn_dicts2"; m.sleep_ms(100); m.lexicon_dir = tmp .. "/cn_dicts"
t0 = os.time()
repeat m.sleep_ms(50) until m.word_freq("家里") == 999 or os.time() - t0 > 60
check("extra.dict.yaml 被读入（家里的权重取最大 999）", m.word_freq("家里") == 999, tostring(m.word_freq("家里")))
os.remove(tmp .. "/cn_dicts/extra.dict.yaml")

-- 换词典目录：只重读词典索引，模型不重载（换的期间生成不可用，读完后用新词典）
os.execute("mkdir -p '" .. tmp .. "/cn_dicts2'")
f = io.open(tmp .. "/cn_dicts2/base.dict.yaml", "w")
f:write("---\nname: base\n...\n经理\tjing li\t100\n精力\tjing li\t90\n")
f:close()
local loads_before = tonumber(m.status():match('"loads":(%d+)'))
m.lexicon_dir = tmp .. "/cn_dicts2"
t0 = os.time()
repeat
  m.sleep_ms(50)
  r, why = m.generate(CTX, "jl", 10, true)
until (r and #r == 2) or os.time() - t0 > 60
check("换词典目录后 jl 只剩新词典里的两个词", r and #r == 2, r and tostring(#r) or tostring(why))
-- 同一个（上文, 输入）在换词典之前生成过：结果必须按新词典重算，不能把缓存里旧词典的分数套到新词上
do
  local ws = {}
  for i, x in ipairs(r or {}) do ws[i] = x.w end
  local sr = m.score(CTX, ws, true)
  local ss = m.get_scores()
  local fresh = sr ~= nil and ss ~= nil
  for i, x in ipairs(r or {}) do
    if not ss or math.abs((ss[x.w] or -1e9) - x.s) > 1e-3 or sr[i] ~= x.w then fresh = false end
  end
  check("换词典目录后生成的分数与逐个打分一致（没有沿用旧词典的缓存）", fresh)
end
check("换词典目录没有重载模型", tonumber(m.status():match('"loads":(%d+)')) == loads_before)
check("换词典后 word_freq 跟着换", m.word_freq("教练") == nil and m.word_freq("经理") == 100)
m.lexicon_dir = tmp .. "/cn_dicts"
t0 = os.time()
repeat m.sleep_ms(50); r = m.generate(CTX, "jl", 10, true) until (r and #r == 6) or os.time() - t0 > 60
check("换回原目录后恢复 6 个词", r and #r == 6)

-- 6) score_gen：第一阶段的打分与生成合成一次请求，结果与分别调用相同
m.budget_ms = 2000
local C2 = {"经理", "经历", "精力", "家里"}
local function same_list(a, b)
  if not a or not b or #a ~= #b then return false end
  for i = 1, #a do if a[i] ~= b[i] then return false end end
  return true
end
local sr = m.score(CTX, C2, false)
local s_sep = m.get_scores()
local gr, gn = m.generate(CTX, "jl", 10, false)
local q = m.score_gen(CTX, C2, "jl", 10)
local g_same = q.gen_ok and gr ~= nil and #q.gen == #gr and q.gen_n == gn
if g_same then
  for i, x in ipairs(gr) do
    local y = q.gen[i]
    if y.w ~= x.w or y.r ~= x.r or math.abs(y.s - x.s) > 1e-9 then g_same = false end
  end
end
check("score_gen both：排序与 score 相同", q.ok and same_list(q.rank, sr))
check("score_gen both：生成结果与 generate 相同（词、分数、名次、匹配数）", g_same)
local s_sg = m.get_scores()
local sc_same = s_sg ~= nil and s_sep ~= nil
for _, w in ipairs(C2) do if not sc_same or s_sg[w] ~= s_sep[w] then sc_same = false end end
check("score_gen 之后 get_scores 返回本次打分", sc_same)
q = m.score_gen(CTX, C2, "jingli", 10)
check("score_gen both，全拼：打分照常，生成 not_applicable", q.ok and not q.gen_ok and q.gen_why == "not_applicable",
  tostring(q.gen_why))
q = m.score_gen(CTX, C2, "jingli", 10, "gen_first")
check("score_gen gen_first，生成不适用：不打分", not q.ok and q.why == "not_tried" and q.gen_why == "not_applicable",
  tostring(q.why) .. " " .. tostring(q.gen_why))
q = m.score_gen(CTX, C2, "jl", 10, "gen_first")
check("score_gen gen_first，能生成：打分与生成都有", q.ok and q.gen_ok and same_list(q.rank, sr))
q = m.score_gen(CTX, {"经理"}, "jl", 10)
check("score_gen both，候选不足：不生成", not q.ok and q.why == "few_candidates" and q.gen_why == "not_tried",
  tostring(q.why) .. " " .. tostring(q.gen_why))
q = m.score_gen("短", C2, "jl", 10)
check("score_gen both，上文不足：no_context，不生成", not q.ok and q.why == "no_context" and q.gen_why == "not_tried",
  tostring(q.why))
-- 超时：打分任务照样算完进缓存（排在它后面的生成任务被撤掉，工作线程不会因此丢掉打分任务）
local CTX3 = "昨天下午我们在会议室里讨论了很久，最后决定由他来当"
local hits0 = tonumber(m.status():match('"cache_hits":(%d+)'))
m.budget_ms = 0.01
q = m.score_gen(CTX3, C2, "jl", 10)
m.budget_ms = 2000
check("score_gen 超时：打分返回 timeout", not q.ok and q.why == "timeout", tostring(q.why))
m.sleep_ms(500)
local r3 = m.score(CTX3, C2, false)
check("score_gen 超时后，同样的打分直接命中缓存", r3 ~= nil and tonumber(m.status():match('"cache_hits":(%d+)')) == hits0 + 1)
check("状态里记录 score_gen 次数", tonumber(m.status():match('"score_gen":(%d+)')) == 7)
-- 共用一次解码：打分与生成都没命中缓存时合成一个任务（状态里的 shared 加一）；关掉 share_decode 后各排一个
local function shared() return tonumber(m.status():match('"shared":(%d+)')) end
local sh0 = shared()
q = m.score_gen("上个月我们部门新来了一位", C2, "jl", 10)
check("score_gen 两边都要算：合成一个任务", q.ok and q.gen_ok and #q.gen > 0 and shared() == sh0 + 1, tostring(shared() - sh0))
local same_set = q.ok and #q.rank == #C2
check("合成任务的打分覆盖全部候选", same_set)
m.share_decode = false
q = m.score_gen("上个月我们部门新来了两位", C2, "jl", 10)
m.share_decode = true
check("share_decode 关闭：不合成任务，结果照常", q.ok and q.gen_ok and shared() == sh0 + 1)

if fails > 0 then print(fails .. " FAILED"); os.exit(1) end
print("ALL PASS")
os.exit(0)
