-- 光标上文读取（ctxlink_win.cpp）去掉周期读取后的实机探针：在前台窗口里准备好文字，按步骤调用 llm_context，
-- 只打印来源、汉字数与时间，不打印文字本身。由 tests/windows/ctx_event_probe.ps1 驱动（它负责打开记事本、改文字）。
-- 用法：lua54 ctx_event_probe.lua <rime_llm.so 所在目录> <步骤>，步骤：first | again
local so_dir, step = arg[1], arg[2]
package.cpath = so_dir .. "/?.so;" .. package.cpath
local m = assert(require("rime_llm"))
local function han(t)
    local n = 0
    for _, cp in utf8.codes(t or "") do if cp >= 0x4E00 and cp <= 0x9FFF then n = n + 1 end end
    return n
end
local function sleep_ms(ms) local t = os.clock() + ms / 1000; while os.clock() < t do end end
local function probe(tag)
    local t, src = m.llm_context()
    print(string.format("%-28s src=%-4s han=%d", tag, tostring(src), han(t)))
    return t
end
-- 第一次调用：快照为空，应立即补读（不等 50 ms），稍后就能拿到
probe("call 1 (empty snapshot)")
sleep_ms(200)
local a = probe("call 2 (+200 ms)")
-- 保持进程，按驱动脚本的节奏再读：驱动脚本在 stdin 写一行就读一次
for line in io.lines() do
    if line == "quit" then break end
    probe(line)
end
