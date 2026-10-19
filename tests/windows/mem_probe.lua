-- Resident memory of the in-process core on Windows (the figure the README table calls "常驻内存").
-- Phases: 0 bare interpreter, 1 module loaded (no model), 2 model loaded and first scoring done, 3 after 300 scorings (steady state),
--         4 after the idle-unload path (idle_unload_sec) when requested.
-- All numbers in MB from os.mem(): private (commit), ws (working set, like VmRSS), ws_priv (private part of ws, like anonymous RSS),
-- ws_shared (file-backed and shared pages: the mmap'd model file, system DLLs).
-- usage: lua54 mem_probe.lua <dir of rime_llm.so> <suzu.gguf> <suzu.vocab.txt> <log dir> <threads> [unload]
local so_dir, model, vocab, logd, threads, unload = arg[1], arg[2], arg[3], arg[4], tonumber(arg[5] or "4"), arg[6] == "unload"
local function snap(tag)
  collectgarbage("collect")
  local m = os.mem()
  print(string.format("%-34s private=%6.1f  ws=%6.1f  ws_priv=%6.1f  ws_shared=%6.1f  peak_ws=%6.1f", tag, m.private, m.ws, m.ws_priv, m.ws_shared, m.ws_peak))
  return m
end
local p0 = snap("0 bare interpreter")
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = require("rime_llm")
m.log_dir = logd
local p1 = snap("1 module loaded, no model")
m.n_threads = threads
m.student_vocab = vocab
if unload then m.idle_unload_sec = 3 end
m.model_path = model
local ctx, cands = "他在公司当", { "经理", "经历", "精力", "敬礼", "警力" }
local t0 = os.now_ms()
while true do
  local r = m.score(ctx, cands, true)
  if r then break end
  if os.now_ms() - t0 > 30000 then print("model did not load"); os.exit(1) end
  os.sleep(0.05)
end
local p2 = snap("2 model loaded, first score")
for i = 1, 300 do m.score("今天天气" .. (i % 7), { "很好", "很号" }, true) end
local p3 = snap("3 steady state (300 scorings)")
local p4
if unload then
  os.sleep(8)
  p4 = snap("4 after idle unload")
end
print(string.format("RESULT threads=%d  model_cost_private=%.1f  model_cost_ws=%.1f  model_cost_ws_priv=%.1f  steady_total_private=%.1f  steady_total_ws=%.1f%s",
  threads, p3.private - p1.private, p3.ws - p1.ws, p3.ws_priv - p1.ws_priv, p3.private, p3.ws,
  p4 and string.format("  after_unload_private=%.1f after_unload_ws=%.1f", p4.private, p4.ws) or ""))
