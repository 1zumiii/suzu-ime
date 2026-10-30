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
-- 保持进程，按驱动脚本的节奏再读：驱动脚本在 stdin 写一行就读一次。
-- 以 ! 开头的是命令（-Mode race 用）：
--   !edit 标签  先 edit_reset_context 再 llm_context，与同一次按键里接入层的调用顺序相同（编辑键之后紧跟预解码取上文）
--   !inv  标签  只调 invalidate_context（英文模式直接打字），不读
for line in io.lines() do
    line = line:gsub("^\239\187\191", "") -- Windows PowerShell 写标准输入时第一行带 BOM，不去掉 !edit 就匹配不上
    if line == "quit" then break end
    local cmd, tag = line:match("^!(%a+)%s*(.*)$")
    if cmd == "edit" then
        m.edit_reset_context()
        probe("edit+pick " .. tag)
    elseif cmd == "inv" then
        if m.invalidate_context then m.invalidate_context(); print("invalidate " .. tag)
        else print("invalidate " .. tag .. ": core has no invalidate_context (old core)") end
    else
        probe(line)
    end
end
