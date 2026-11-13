-- 统一排序加候选生成（默认配置）下，过滤器输出顺序的检查。用桩模块代替核心，不需要模型。
-- 用法：lua unified_order_test.lua <仓库目录> <临时目录>
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
local function mk(text, s, e, ctype, comment)
  return { text = text, _start = s, _end = e, type = ctype or "phrase", comment = comment or "" }
end
function Candidate(ctype, s, e, text, comment) return mk(text, s, e, ctype, comment) end
function ShadowCandidate(c, ctype, text, comment) return mk(text, c._start, c._end, ctype, comment) end
local function mk_env(input, stage2)
  return { engine = {
    schema = { config = {
      get_string = function() return nil end, get_int = function() return nil end,
      get_double = function() return nil end, get_bool = function() return nil end } },
    context = { input = input, composition = { back = function() return {} end },
                get_option = function(_, name) return name == "ai_rerank" or (stage2 and name == "ai_stage2") end } } }
end

-- 第二阶段查剩余拼音用的翻译器：按剩余拼音返回测试指定的候选
local tail_cands = {}
Component = { Translator = function()
  return { query = function(_, rest)
    local l, i = tail_cands[rest] or {}, 0
    return { iter = function() return function() i = i + 1; return l[i] end end }
  end }
end }

-- ---------- 桩核心：模型分按候选的原顺序递减，生成结果由测试指定 ----------
local gen_list, last = {}, {}
_G.ai_rerank_llm = {
  score = function(_, cands) last = {}; for i, w in ipairs(cands) do last[w] = -i end; return cands end,
  get_scores = function() return last end,
  generate = function() return gen_list end,
}
_G.ai_rerank_context_get = function() return "今天天气很好我们一起" end
local filter = require("ai_rerank_filter")

local function run(input, cands, stage2)
  yielded = {}
  local idx = 0
  filter({ iter = function() return function() idx = idx + 1; return cands[idx] end end }, mk_env(input, stage2))
  local texts, pos = {}, {}
  for i, c in ipairs(yielded) do texts[i] = c.text; pos[c.text] = pos[c.text] or i end
  return texts, pos
end

-- 第 2 位是英文、第 4 位是 emoji，其余是覆盖整个输入的汉字候选
local function cands()
  local l = { mk("哦可", 0, 2), mk("OK", 0, 2), mk("欧克", 0, 2), mk("😀", 0, 2) }
  for i = 1, 20 do l[#l + 1] = mk("偶" .. utf8.char(0x4E00 + i), 0, 2) end
  return l
end

do
  gen_list = {}
  local texts, pos = run("ok", cands())
  check("没有生成：英文与 emoji 留在原位", pos["OK"] == 2 and pos["😀"] == 4, table.concat(texts, " ", 1, 6))
  check("没有生成：候选一个不少", #texts == 24, tostring(#texts))
end

do
  gen_list = { { w = "欧克", s = -1, r = 1 } }
  local texts, pos = run("ok", cands())
  check("生成的词 Rime 已给出：英文与 emoji 留在原位", pos["OK"] == 2 and pos["😀"] == 4, table.concat(texts, " ", 1, 6))
  check("生成的词 Rime 已给出：不重复显示", #texts == 24, tostring(#texts))
end

do
  -- 生成出 Rime 没给的新词：重排后的组比原来多一个，组外候选仍留在原位，不能被挤到整组之后
  gen_list = { { w = "奥克", s = -1.5, r = 3 } }
  local texts, pos = run("ok", cands())
  check("生成出新词：新词进了候选", pos["奥克"] ~= nil)
  check("生成出新词：英文与 emoji 留在原位", pos["OK"] == 2 and pos["😀"] == 4,
    string.format("OK=%s emoji=%s", tostring(pos["OK"]), tostring(pos["😀"])))
  check("生成出新词：候选只多出新词这一个", #texts == 25, tostring(#texts))
  local seen, dup = {}, false
  for _, t in ipairs(texts) do if seen[t] then dup = true end; seen[t] = true end
  check("生成出新词：没有重复的候选", not dup)
end

do
  -- 组内有重复文字：重排后的组比原来少，组外候选同样不挪到后面
  gen_list = {}
  local l = cands()
  table.insert(l, 6, mk("哦可", 0, 2))
  local texts, pos = run("ok", l)
  check("组内有重复文字：英文留在原位", pos["OK"] == 2, tostring(pos["OK"]))
end

do
  -- 输入本身是英文词（google）：第二阶段不能把剩余拼音硬拼成四字词（狗讴歌了）放到英文词前面
  gen_list = {}
  _G.ai_rerank_llm.match_code = function() return true end   -- 真核心按正则判断；Lua 模式不认 {2,}
  tail_cands["ogle"] = { mk("歌了", 0, 4), mk("冠了", 0, 4) }
  local function han() return { mk("狗讴", 0, 2), mk("功欧", 0, 2), mk("狗", 0, 1), mk("功", 0, 1) } end
  local function first_s2(texts) return texts[1] == "狗讴歌了" or texts[1] == "功欧歌了" or texts[1] == "狗讴冠了" end

  _G.llm_filter_cache, _G.ai_rerank_state = nil, nil
  local texts = run("google", han(), true)
  check("对照：没有英文词时第二阶段照常拼接", first_s2(texts), table.concat(texts, " ", 1, 4))

  _G.llm_filter_cache, _G.ai_rerank_state = nil, nil
  local l = han()
  table.insert(l, 1, mk("google", 0, 6)); table.insert(l, 2, mk("Google", 0, 6))
  texts = run("google", l, true)
  check("Rime 原首位是英文词：不拼四字词，英文词仍在首位", texts[1] == "google" and not first_s2(texts), table.concat(texts, " ", 1, 4))

  _G.llm_filter_cache, _G.ai_rerank_state = nil, nil
  l = han()
  table.insert(l, 3, mk("Google", 0, 6))
  texts = run("google", l, true)
  local hit = false
  for _, t in ipairs(texts) do if t:find("歌了", 1, true) or t:find("冠了", 1, true) then hit = true end end
  check("英文词不在首位但与输入相同：同样不拼四字词", not hit, table.concat(texts, " ", 1, 5))
end

print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
os.exit(fails == 0 and 0 or 1)
