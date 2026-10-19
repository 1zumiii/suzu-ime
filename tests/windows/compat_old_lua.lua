-- Does the new rime_llm.dll work under the EXISTING Windows Lua scripts (llm_processor.lua / llm_filter.lua)?
-- Calls the module the way those scripts do: properties first (model_path, student_vocab, n_threads, max_ctx, log_dir),
-- then prepare -> is_ready -> score -> get_scores -> status, plus the cursor-context hooks they poll on every key.
-- usage: lua54 compat_old_lua.lua <dir of rime_llm.so> <suzu.gguf> <suzu.vocab.txt> <log dir>
local so_dir, model, vocab, logd = arg[1], arg[2], arg[3], arg[4]
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = require("rime_llm")
local fails = 0
local function check(name, cond, info) print((cond and "PASS " or "FAIL ") .. name .. (info and ("  [" .. tostring(info) .. "]") or "")); if not cond then fails = fails + 1 end end

m.log_dir = logd
m.n_threads = 4
m.max_ctx = 64                  -- kept only for compatibility in the new core
m.student_vocab = vocab
m.model_path = model
check("properties readable back", m.model_path == model and m.student_vocab == vocab and m.n_threads == 4, m.model_path)

local t0 = os.now_ms()
check("prepare returns without blocking (< 50 ms)", (function() m.prepare("他在公司当"); return os.now_ms() - t0 < 50 end)(), string.format("%.1f ms", os.now_ms() - t0))
local ready = false
for _ = 1, 600 do if m.is_ready() then ready = true; break end; os.sleep(0.1) end
check("model becomes ready", ready)
m.prepare("他在公司当")
os.sleep(0.2)
local r, why = m.score("他在公司当", { "经理", "经历", "精力" }, false)
check("score returns a table", type(r) == "table", why)
local sc = m.get_scores()
check("get_scores has every candidate", type(sc) == "table" and sc["经理"] and sc["经历"] and sc["精力"] ~= nil)
check("first choice is 经理", type(r) == "table" and r[1] == "经理", r and table.concat(r, ","))
local st = m.status()
check("status() is a string with \"student\":true (what llm_filter.lua tests)", type(st) == "string" and st:find('"student":true', 1, true) ~= nil, st)
check("match_code works", m.match_code(".+", "nihao") == true and m.match_code(".{4}", "nihao") == false and m.match_code(".{4}", "nihe") == true)
for _, fn in ipairs({ "click_happened", "fg_changed", "kick_context", "edit_reset_context", "llm_context" }) do
  local ok, a = pcall(m[fn])
  check("hook " .. fn .. "() callable", ok, ok and tostring(a) or a)
end
local r2, why2 = m.score("他", { "经理", "经历" }, false)
check("one-character context is not scored (no crash)", r2 == nil, why2)
print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
os.exit(fails == 0 and 0 or 1)
