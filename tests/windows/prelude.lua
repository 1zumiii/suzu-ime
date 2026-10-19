-- Windows adaptation of the test harness (loaded through LUA_INIT=@tests/windows/prelude.lua by the Windows-native lua54).
-- The tests use POSIX shell commands through os.execute / io.popen. Under a native Windows interpreter those go to cmd.exe and fail,
-- so this prelude routes them to Git Bash (written to a temp script to avoid quoting problems) and replaces the two probes that
-- have no shell equivalent (process memory: footprint or /proc VmRSS, thread count: /proc task or ps -M) with the native
-- os.rss_mb() and os.nthreads() of the test interpreter. Core code is not touched; only how the tests talk to the operating system.
local real_popen, real_execute = io.popen, os.execute
local tmp = (os.getenv("TEMP") or "C:/Windows/Temp"):gsub("\\", "/")
local seq = 0

local function script_for(cmd)
  seq = seq + 1
  local p = string.format("%s/suzu_sh_%d_%d.sh", tmp, os.getpid(), seq)
  local f = assert(io.open(p, "wb"))
  f:write(cmd:gsub("\r", ""), "\n")
  f:close()
  return p
end

local function fake(value)
  return { read = function(_, fmt)
             if fmt == "n" or fmt == "*n" then return tonumber(value) end
             return tostring(value) .. "\n"
           end,
           close = function() return true end, lines = function() return function() return nil end end }
end

io.popen = function(cmd, mode)
  if cmd:find("footprint", 1, true) or cmd:find("VmRSS", 1, true) then return fake(os.rss_mb() or "") end
  if cmd:find("/proc/$PPID/task", 1, true) or cmd:find("ps -M", 1, true) then return fake(os.nthreads() or 0) end
  local p = script_for(cmd)
  local h = real_popen('bash "' .. p .. '"', mode or "r")
  return h
end

os.execute = function(cmd)
  if not cmd then return true end
  local secs = cmd:match("^sleep%s+([%d%.]+)%s*$")
  if secs then   -- plain sleeps: native, no shell
    os.sleep(tonumber(secs))
    return true
  end
  local p = script_for(cmd)
  return real_execute('bash "' .. p .. '"')
end

-- The old core has no now_ms / sleep_ms (new in the rewrite). Fill them in (rawset: the module table has __newindex for properties)
-- so the same key simulation can drive both cores.
local real_require = require
require = function(name)
  local m = real_require(name)
  if name == "rime_llm" and type(m) == "table" then
    if rawget(m, "now_ms") == nil then rawset(m, "now_ms", os.now_ms) end
    if rawget(m, "sleep_ms") == nil then rawset(m, "sleep_ms", function(ms) os.sleep(ms / 1000) end) end
  end
  return m
end
