-- Probe for the cursor-context bypass (UIA): run while a test window with a caret after some text is in the foreground.
-- usage: lua54 ctx_probe.lua <dir of rime_llm.so> <log dir>   prints numbers and the (test) text it got, one line per try
package.cpath = arg[1] .. "/?.so;" .. package.cpath
local m = require("rime_llm")
m.log_dir = arg[2]
local got
for i = 1, 24 do
  pcall(m.kick_context)
  os.sleep(0.25)
  local ok, t, src = pcall(m.llm_context)
  print(string.format("try %2d: ok=%s type=%s len=%s src=%s", i, tostring(ok), type(t), t and #t or "-", tostring(src)))
  if ok and t and #t > 0 then got = t; print("context: " .. t); break end
end
print("fg_changed=" .. tostring(m.fg_changed()) .. " click_happened=" .. tostring(m.click_happened()))
print(got and "GOT CONTEXT" or "NO CONTEXT")
