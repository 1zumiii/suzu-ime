-- 只用模型给候选打分（corpus_report.py --rescore 的辅助脚本）。
-- 从标准输入逐行读“上文<Tab>词|词…”，向标准输出逐行写“分|分…”（与输入同序）；上文不足 2 个字等不能打分时写 skip。
-- 不写任何文件。
-- 用法：lua corpus_rescore.lua <rime_llm.so 所在目录> <suzu.gguf> <suzu.vocab.txt>
local so_dir, model, vocab = arg[1], arg[2], arg[3]
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = require("rime_llm")
m.log_dir = os.getenv("TMPDIR") or "/tmp"
m.model_path = model
m.student_vocab = vocab
m.sync_budget_ms = 0   -- 一直等到算完
local t0 = os.time()
while true do
  local r, why = m.score("他在公司当", { "经理", "经历" }, true)
  if r then break end
  if why == "no_model" or os.time() - t0 > 60 then io.stderr:write("model not ready: " .. tostring(why) .. "\n"); os.exit(1) end
  if m.sleep_ms then m.sleep_ms(100) else os.execute("sleep 0.1") end
end
for ln in io.lines() do
  local ctx, cs = ln:match("^([^\t]*)\t(.*)$")
  local cands = {}
  for c in (cs or ""):gmatch("[^|]+") do cands[#cands + 1] = c end
  local r = ctx and #cands >= 2 and m.score(ctx, cands, true)
  local sc = type(r) == "table" and m.get_scores()
  if sc then
    local parts = {}
    for i, w in ipairs(cands) do parts[i] = string.format("%.4f", sc[w] or -1e9) end
    io.write(table.concat(parts, "|"), "\n")
  else
    io.write("skip\n")
  end
end
