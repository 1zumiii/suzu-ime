-- 空闲自动卸载测试
-- 用法：lua idle_test.lua <rime_llm.so 所在目录> <suzu.gguf> <日志目录> [close|exit]
--   词表按模型文件名找同名的 .vocab.txt（suzu.gguf → suzu.vocab.txt），也可用环境变量 SUZU_VOCAB 指定
local so_dir, model, log_dir, exit_mode = arg[1], arg[2], arg[3], arg[4] or "close"
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = require("rime_llm")
m.log_dir = log_dir
if os.getenv("SUZU_VOCAB") then m.student_vocab = os.getenv("SUZU_VOCAB") end
m.model_path = model
m.n_threads = 2

local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end
-- 本进程的内存（MB）：macOS 用 footprint，Linux 用 /proc 的 RSS
local function fp()
  local f = io.popen("footprint -p $PPID 2>/dev/null | sed -n 's/.*Footprint: \\([0-9]*\\) MB.*/\\1/p'; " ..
                     "awk '/VmRSS/ {print int($2/1024)}' /proc/$PPID/status 2>/dev/null")
  local v = tonumber((f:read("*a") or ""):match("%d+")); f:close(); return v
end
local function scoreit() return m.score("他在公司当", { "经理", "经历", "精力" }) end
local function wait_ready(limit)
  local t0 = os.time()
  while true do
    local r, why = scoreit()
    if r then break end
    if why == "no_model" then print("FAIL: model failed to load (see rime_llm_log.txt)"); os.exit(1) end
    os.execute("sleep 0.1"); if os.time() - t0 > (limit or 60) then return false end
  end
  return true
end

check("default idle_unload_sec is 0 (off)", m.idle_unload_sec == 0, tostring(m.idle_unload_sec))
m.idle_unload_sec = 1
check("idle_unload_sec can be set", m.idle_unload_sec == 1, tostring(m.idle_unload_sec))

check("model loads", wait_ready())
local loaded_mb = fp()

-- 连续使用：每 0.3 秒打一次分，持续 3 秒（> 1 秒空闲时限），不应被卸载
for i = 1, 10 do scoreit(); os.execute("sleep 0.3") end
check("continuous use (every 0.3 s for 3 s, limit 1 s): NOT unloaded", m.is_ready() == true)

-- 空闲：2.5 秒不用 → 应被卸载并还回内存
os.execute("sleep 2.5")
check("idle > limit: unloaded", m.is_ready() == false)
local idle_mb = fp()
-- Suzu 的权重是文件映射（干净页）加约 20 MB 的 KV 与计算缓冲区：卸载后应至少还回 20 MB
check("memory returned after idle unload", idle_mb and loaded_mb and idle_mb < loaded_mb - 20,
      string.format("%s MB -> %s MB", tostring(loaded_mb), tostring(idle_mb)))

-- 再用：自动加载回来，结果正确
local t0 = os.time()
check("transparently reloads on next use", wait_ready())
local ranked = scoreit()
check("result correct after reload", ranked and ranked[1] == "经理", ranked and table.concat(ranked, ">") or "nil")

-- 关闭配置：不再卸载
m.idle_unload_sec = 0
os.execute("sleep 2.5")
check("idle_unload_sec = 0: never unloads", m.is_ready() == true)

-- 再次开启并立即退出，确认退出路径干净（模型仍加载着）
m.idle_unload_sec = 1
print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
io.stdout:flush()
if exit_mode == "exit" then os.exit(fails == 0 and 0 or 1) end
