-- 边界情况测试。用法：lua concurrency_test.lua <so目录> <suzu.gguf> <日志目录> <场景>
-- 场景：race | loading | reinit | toggles | exit_wait | exit_window
--   词表按模型文件名找同名的 .vocab.txt（suzu.gguf → suzu.vocab.txt），也可用环境变量 SUZU_VOCAB 指定。
-- 新核心里模型只由工作线程创建、使用、释放，调用方从不碰它；这里验证的是：卸载、加载、重新初始化、
-- 退出这些时刻与输入交错时，调用都在时限内返回、不崩溃，之后能自动恢复。
local so_dir, model, log_dir, case = arg[1], arg[2], arg[3], arg[4]
package.cpath = so_dir .. "/?.so;" .. package.cpath

local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end
local function sh(cmd) local f = io.popen(cmd); local r = f:read("*a"); f:close(); return r end
-- 本进程的线程数：macOS 用 ps -M，Linux 用 /proc
local function nthreads()
  local v = tonumber((sh("ls /proc/$PPID/task 2>/dev/null | wc -l"):match("%d+")))
  if not v or v == 0 then v = tonumber(sh("ps -M -p $PPID | tail -n +2 | wc -l"):match("%d+")) end
  return v
end
local cands = { "经理", "经历", "精力" }
local function setup(m)
  m.log_dir = log_dir
  if os.getenv("SUZU_VOCAB") then m.student_vocab = os.getenv("SUZU_VOCAB") end
  m.model_path = model
  m.n_threads = 2
end
local function wait_ready(m, limit)
  local t0 = os.time()
  while true do
    local r, why = m.score("他在公司当", cands)
    if r then break end
    if why == "no_model" then print("FAIL: model failed to load (see rime_llm_log.txt)"); os.exit(1) end
    os.execute("sleep 0.05"); if os.time() - t0 > (limit or 60) then return false end
  end
  return true
end
local function finish()
  print(fails == 0 and "ALL OK" or (fails .. " FAILED"))
  io.stdout:flush()
end

local m = require("rime_llm")
setup(m)
local now = m.now_ms

if case == "race" then
  -- 卸载的那一刻用户正在打字：空闲时限 1 秒，在卸载前后的 3 秒里不停地打分与预解码
  m.idle_unload_sec = 1
  check("model loads", wait_ready(m))
  m.score("他在公司当", cands)
  os.execute("sleep 0.9")                      -- 接近空闲时限
  local worst, n, reasons, saw_unloaded = 0, 0, {}, false
  local t_end = now() + 3000
  while now() < t_end do
    local a = now()
    local r, why = m.score("他在公司当" .. string.rep("的", n % 3), cands, false)
    m.prepare("他在公司当")
    local dt = now() - a
    if dt > worst then worst = dt end
    if not r then reasons[tostring(why)] = true end
    if not m.is_ready() then saw_unloaded = true end
    n = n + 1
    if n % 50 == 0 then os.execute("sleep 1.2") end   -- 偶尔停一下，让空闲卸载真的发生
  end
  local rs = {}
  for k in pairs(reasons) do rs[#rs + 1] = k end
  check("calls across the unload moment: no crash", n > 50, tostring(n))
  check("every call returned within the time limit (<60 ms)", worst < 60, string.format("%.1f ms", worst))
  check("model was unloaded at some point", saw_unloaded)
  check("skips during unload/reload carry a reason", #rs > 0, table.concat(rs, ","))
  m.idle_unload_sec = 0
  check("transparently reloads afterwards", wait_ready(m))
  local ranked = m.score("他在公司当", cands)
  check("result correct after the race", ranked and ranked[1] == "经理", ranked and table.concat(ranked, ">") or "nil")
  finish()

elseif case == "loading" then
  -- 模型正在加载时反复收到输入：不重复加载、不丢最新上下文
  local calls, worst = 0, 0
  for i = 1, 300 do
    local a = now()
    m.prepare("他在公司当"); m.score("他在公司当", cands)
    worst = math.max(worst, now() - a)
    calls = calls + 1
  end
  m.prepare("我最近没有太多")
  check("many calls while loading", calls > 20, tostring(calls))
  check("calls while loading return fast (<10 ms)", worst < 10, string.format("%.1f ms", worst))
  check("model becomes ready", wait_ready(m))
  local f = io.open(log_dir .. "/rime_llm_log.txt"); local log = f:read("*a"); f:close()
  local _, loads = log:gsub("loading model:", "")
  check("model created exactly once", loads == 1, tostring(loads))
  check("prepare(latest) is accepted once loaded", m.prepare("我最近没有太多") == true)
  os.execute("sleep 0.3")
  local r, why = m.score("我最近没有太多", { "经理", "经历", "精力", "敬礼", "警力" }, false)
  check("scoring with the latest context works (prepared)", type(r) == "table" and r[1] == "精力", r and table.concat(r, ">") or ("nil/" .. tostring(why)))
  finish()

elseif case == "reinit" then
  -- 模块被回收（__gc）后重新 require：工作线程不泄漏，空闲卸载仍然有效
  m.idle_unload_sec = 1
  check("model loads", wait_ready(m))
  local th1 = nthreads()
  m = nil
  package.loaded["rime_llm"] = nil
  collectgarbage(); collectgarbage()           -- 触发旧模块表的 __gc：停止工作线程并释放模型
  local th_after_gc = nthreads()
  check("module gc stops the worker and its thread pool", th_after_gc < th1, string.format("%d -> %d", th1, th_after_gc))
  local m2 = require("rime_llm")
  setup(m2)
  m2.idle_unload_sec = 1
  check("re-initialised module is a fresh table that can load", wait_ready(m2))
  local th2 = nthreads()
  check("no thread leak across re-init", th2 <= th1, string.format("%d -> %d", th1, th2))
  os.execute("sleep 2")
  check("idle unload still works after re-init", m2.is_ready() == false)
  check("works again afterwards", wait_ready(m2))
  finish()

elseif case == "toggles" then
  -- 配置反复切换：线程数不变，最后一次设置生效
  m.idle_unload_sec = 3
  check("model loads", wait_ready(m))
  local th0 = nthreads()
  for i = 1, 200 do m.idle_unload_sec = (i % 4 == 0) and 0 or (i % 7 + 1) end
  m.idle_unload_sec = 1
  os.execute("sleep 0.5")
  local th1 = nthreads()
  check("200 config changes: thread count unchanged", th1 <= th0, string.format("%d -> %d", th0, th1))
  os.execute("sleep 1.8")
  check("last setting (1 s) still takes effect", m.is_ready() == false)
  finish()

elseif case == "exit_wait" then
  -- 工作线程空闲等待时退出
  m.idle_unload_sec = 600
  check("model loads", wait_ready(m))
  finish()
  os.exit(fails == 0 and 0 or 1)

elseif case == "exit_window" then
  -- 工作线程正在算（超时被放弃的任务还在跑）时退出
  check("model loads", wait_ready(m))
  m.budget_ms = 0.01
  for i = 1, 20 do m.score("退出时还在算的上文第" .. i .. "次", { "经理", "经历", "精力", "敬礼" }, false) end
  finish()
  os.exit(fails == 0 and 0 or 1)
end
