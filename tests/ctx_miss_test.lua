-- 上文未命中归因（状态里的 ctx_miss）的测试：逐一构造每一类，检查只有对应的计数加一。
-- 用法：lua ctx_miss_test.lua <rime_llm.so 所在目录> <suzu.gguf> <suzu.vocab.txt> <临时目录>
local so_dir, model, vocab, tmp = arg[1], arg[2], arg[3], arg[4]
package.cpath = so_dir .. "/?.so;" .. package.cpath
local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end
os.execute("mkdir -p '" .. tmp .. "'")

local m = require("rime_llm")
m.log_dir, m.model_path, m.student_vocab = tmp, model, vocab
m.budget_ms, m.sync_budget_ms = 0, 0   -- 0 = 等到算完
local CANDS = { "经理", "经历", "精力" }
local t0 = os.time()
while not m.is_ready() do m.prepare("他在公司当"); m.sleep_ms(50); if os.time() - t0 > 60 then print("FAIL: model never ready"); os.exit(1) end end
m.score("他在公司当", CANDS, true)

local KINDS = { "no_prepare", "other_ctx", "queued", "replaced", "evicted" }
local function snap()
  local s, st = {}, m.status()
  for _, k in ipairs(KINDS) do s[k] = tonumber(st:match('"' .. k .. '":{"n":(%d+)')) end
  s.late = tonumber(st:match('"late":(%d+)'))
  return s
end
-- expect：应当各加几；ignore：这一步里顺带算的忙任务会计入的类别，不检查
local function expect(name, before, want, ignore)
  local after, bad = snap(), {}
  for _, k in ipairs({ "no_prepare", "other_ctx", "queued", "replaced", "evicted", "late" }) do
    local d = (after[k] or -1) - (before[k] or 0)
    if not (ignore and ignore[k]) and d ~= (want[k] or 0) then bad[#bad + 1] = k .. "+" .. d end
  end
  check(name, #bad == 0, table.concat(bad, " "))
end
-- 让工作线程忙一阵：长上文整段解码，1 ms 就返回，任务在后台继续算
local function busy(ctx)
  m.budget_ms = 1
  m.score(ctx, CANDS, false)
  m.budget_ms = 0
end
local LONG1 = "昨天下午我们在图书馆的二楼讨论了很久关于明年春天出去旅行的具体安排"
local LONG2 = "早上起来发现外面下了很大的雪于是决定今天在家里好好整理一下房间和书架"

-- 1) 预解码完成后打分：上文完全命中，不计数
local s = snap()
m.prepare("今天天气很好我们去公园"); m.sleep_ms(300)
m.score("今天天气很好我们去公园", CANDS, true)
expect("预解码命中：不计数", s, {})

-- 2) 刚预解码过别的上文，打分用的却是另一段：other_ctx
s = snap()
m.prepare("明天要去医院复查"); m.sleep_ms(300)
m.score("晚上回家路上买了", CANDS, true)
expect("准备的是另一段上文：other_ctx", s, { other_ctx = 1 })

-- 3) 紧接着才来同一段上文的预解码：late
s = snap()
m.prepare("晚上回家路上买了")
expect("同一段上文的预解码来晚了：late", s, { late = 1 })

-- 4) 3 秒以上没有任何预解码：no_prepare
m.sleep_ms(3200)
s = snap()
m.score("周末和朋友一起看了", CANDS, true)
expect("近期没有预解码：no_prepare", s, { no_prepare = 1 })

-- 5) 预解码还在排队，打分抢先：queued
s = snap()
busy(LONG1)
m.prepare("老师让我们下周交")
m.score("老师让我们下周交", CANDS, true)
expect("预解码还在排队：queued", s, { queued = 1 }, { no_prepare = true, other_ctx = true })
m.sleep_ms(300)

-- 6) 预解码没轮到就被新的请求顶掉：replaced
s = snap()
busy(LONG2)
m.prepare("妈妈打电话问我什么时候")
m.prepare("公司楼下新开了一家")
m.score("妈妈打电话问我什么时候", CANDS, true)
expect("预解码被顶掉：replaced", s, { replaced = 1 }, { no_prepare = true, other_ctx = true })
m.sleep_ms(300)

-- 7) 准备好了，两个槽后来又被别的上文占用：evicted
s = snap()
m.prepare("这本书我已经看了"); m.sleep_ms(300)
m.prepare("下个月房租要涨"); m.sleep_ms(300)
m.prepare("孩子放学回来说"); m.sleep_ms(300)
m.score("这本书我已经看了", CANDS, true)
expect("准备过但槽位被改写：evicted", s, { evicted = 1 })

-- 8) 状态里有这个字段，格式正确
check("状态里有 ctx_miss 字段", m.status():match('"ctx_miss":{"no_prepare":{"n":%d+,"long":%d+,"ms":%d+}') ~= nil)

print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
os.exit(fails == 0 and 0 or 1)
