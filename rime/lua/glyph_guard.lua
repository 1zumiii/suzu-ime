-- glyph_guard.lua — 本机字体画不出的字（显示成方框或问号的字）
-- 清单：用户目录的 glyph_missing.txt，由各平台按本机字体生成（macOS：macos/glyph_scan.swift；Android：App 启动时
--       在后台用 Paint.hasGlyph 扫描，见 suzu/GlyphScan.kt）。每行一个码位或一个区间（十六进制，如 2A6E0-2A6FF），# 开头为注释。
--       文件不存在时什么都不拦（Windows 暂未生成清单）。
-- 用法：每次过滤调用一次 refresh()，再对候选文字调用 bad(text)。由 ai_rerank_filter 在输入和输出两处调用，
--       AI 造出来的候选（两阶段拼接、候选生成）同样经过检查。
-- 文件最多每 10 秒检查一次大小，大小变了才重读。

local M = {}

local CHECK_SEC = 10
local st = { lo = {}, hi = {}, n = 0, size = -1, checked = nil }

local function path()
    local ok, ud = pcall(function() return rime_api.get_user_data_dir() end)
    if ok and type(ud) == "string" and ud ~= "" then return ud .. "/glyph_missing.txt" end
    return nil
end

local function parse(fh)
    local runs = {}
    for line in fh:lines() do
        local a, b = line:match("^(%x+)%-(%x+)")
        if not a then a = line:match("^(%x+)"); b = a end
        if a then
            a, b = tonumber(a, 16), tonumber(b, 16)
            if a and b and b >= a then runs[#runs + 1] = { a, b } end
        end
    end
    table.sort(runs, function(x, y) return x[1] < y[1] end)
    local lo, hi = {}, {}
    for i, r in ipairs(runs) do lo[i], hi[i] = r[1], r[2] end
    return lo, hi
end

function M.refresh(force)
    local now = os.time()
    if not force and st.checked and now - st.checked < CHECK_SEC then return end
    st.checked = now
    local p = path()
    local fh = p and io.open(p, "r")
    if not fh then
        st.lo, st.hi, st.n, st.size = {}, {}, 0, -1
        return
    end
    local size = fh:seek("end") or 0
    if size ~= st.size then
        fh:seek("set")
        local ok, lo, hi = pcall(parse, fh)
        if ok then st.lo, st.hi, st.n, st.size = lo, hi, #lo, size end
    end
    fh:close()
end

local function missing(cp)
    local lo, hi = st.lo, st.hi
    local a, b = 1, st.n
    while a <= b do
        local m = (a + b) // 2
        if cp < lo[m] then b = m - 1
        elseif cp > hi[m] then a = m + 1
        else return true end
    end
    return false
end

-- 文字里有画不出的字（或不是合法的 UTF-8）时返回 true；没有清单时恒为 false
function M.bad(text)
    if st.n == 0 or type(text) ~= "string" then return false end
    if not text:find("[\226-\244]") then return false end   -- 码位都在 U+2000 以下：清单不管这一段，直接放行
    local ok, res = pcall(function()
        for _, cp in utf8.codes(text) do
            if cp >= 0x2000 and missing(cp) then return true end
        end
        return false
    end)
    return (not ok) or res
end

function M.count() return st.n end

return M
