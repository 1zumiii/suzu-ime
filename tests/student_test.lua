-- Suzu 学生模型测试：加载、排序合理性、平均耗时（进程内，与 Squirrel 里的用法一致）
-- 用法：lua student_test.lua <rime_llm.so 所在目录> <suzu.gguf> <suzu.vocab.txt> <日志目录> [线程数]
local so_dir, model, vocab, log_dir, threads = arg[1], arg[2], arg[3], arg[4], tonumber(arg[5] or "4")
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = require("rime_llm")
m.log_dir = log_dir
m.model_path = model
m.student_vocab = vocab
m.n_threads = threads

local cases = {
  { "他在公司当", { "经理", "经历", "精力" }, "经理" },
  { "他生病后住进了", { "医院", "议员", "意愿" }, "医院" },
  { "国会里的一位", { "医院", "议员", "意愿" }, "议员" },
  { "这是一起严重的", { "时间", "事件", "实践" }, "事件" },
  { "理论必须联系", { "时间", "事件", "实践" }, "实践" },
  { "老师给了我很多", { "知道", "指导", "之道" }, "指导" },
  { "他对这个方案提出了", { "意义", "异议", "一一" }, "异议" },
  { "数学课上老师讲了", { "公式", "共识", "公示" }, "公式" },
}

local t0 = os.time()
while true do
  local r, why = m.score(cases[1][1], cases[1][2])
  if r then break end
  if why == "no_model" then print("FAIL: model failed to load (see rime_llm_log.txt)"); os.exit(1) end
  if os.time() - t0 > 60 then print("FAIL: model not ready in 60s"); os.exit(1) end
  os.execute("sleep 0.2")
end
print(string.format("model ready after ~%ds", os.time() - t0))

local hit = 0
for _, c in ipairs(cases) do
  local s = m.score(c[1], c[2])
  local ok = s and s[1] == c[3]
  if ok then hit = hit + 1 end
  print(string.format("%s | %s -> %s (expect %s) %s", c[1], table.concat(c[2], "/"), s and table.concat(s, ">") or "nil", c[3], ok and "OK" or "MISS"))
end
print(string.format("top-1: %d/%d", hit, #cases))

local N = 600
local start = os.time()
for i = 1, N do
  local c = cases[(i % #cases) + 1]
  m.score(c[1] .. (i % 7 == 0 and "的" or ""), c[2])
end
local wall = os.time() - start
print(string.format("%d scores in ~%ds (about %.1f ms each, wall time at 1 s resolution)", N, wall, wall * 1000 / N))

-- 词表外的字（<unk>）：模型分不可信，须排到末尾；否则单个生僻字的得分会高于任何真实的词
do
  local cands = { "上游", "𦜳", "三个月", "𬳵" }
  m.score("遗留项目", cands)
  local r = m.score("遗留项目", cands)
  local sc = m.get_scores()
  local oov_last = r and r[3] and r[4] and (r[3] == "𦜳" or r[3] == "𬳵") and (r[4] == "𦜳" or r[4] == "𬳵")
  print((oov_last and "ok" or "FAIL") .. " OOV candidates are ranked last: " .. (r and table.concat(r, ">") or "nil"))
  print(((sc["𦜳"] or 0) <= -1e9 and "ok" or "FAIL") .. " OOV candidate has an invalid score (<= -1e9)")
end
