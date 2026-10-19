-- 留出集逐题打分：输出与 reference/holdout-parity/res_q8.tsv 同格式（编号 \t 0 \t 词:分|词:分…，按分数降序）。
-- 新旧核心都能跑（只用 score/get_scores），用于核对两者逐题一致。
-- 用法：lua run_cases.lua <rime_llm.so 所在目录> <suzu.gguf> <suzu.vocab.txt> <cases.tsv> <输出> [线程数]
local so_dir, model, vocab, cases, outp, threads = arg[1], arg[2], arg[3], arg[4], arg[5], tonumber(arg[6] or "4")
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = require("rime_llm")
m.log_dir = os.getenv("TMPDIR") or "/tmp"
m.model_path = model
m.student_vocab = vocab
m.n_threads = threads
m.sync_budget_ms = 0   -- 新核心：一直等到算完（旧核心没有这个属性，赋值无效果）
local t0 = os.time()
while not m.score("他在公司当", { "经理", "经历", "精力" }, true) do
  if os.time() - t0 > 60 then print("not ready"); os.exit(1) end
  os.execute("sleep 0.2")
end
local out = assert(io.open(outp, "w"))
local n, skipped = 0, 0
for ln in io.lines(cases) do
  local i, ci, ctx, cs = ln:match("^(%d+)\t(%d+)\t([^\t]*)\t(.*)$")
  local cands = {}
  for c in cs:gmatch("[^|]+") do cands[#cands + 1] = c end
  local r = m.score(ctx, cands, true)
  if type(r) == "table" then
    local sc = m.get_scores()
    local parts = {}
    for _, w in ipairs(r) do parts[#parts + 1] = w .. ":" .. string.format("%.3f", sc[w] or 0) end
    out:write(i, "\t", ci, "\t", table.concat(parts, "|"), "\n")
    n = n + 1
  else
    skipped = skipped + 1
  end
end
out:close()
print(string.format("scored %d, skipped %d", n, skipped))
