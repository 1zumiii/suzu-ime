-- input_log.lua — 输入事件流（默认关闭）
-- 只记事实，不做推断：选词、上屏、删除、失步。句子、纠错、词对等由 tools/input_log.py 离线重放推导。
-- 设计见私有文档 ime/2026-10-20-input-event-log-and-unified-ranking.md。
-- 开关：用户目录里存在空文件 input_log.enabled。文件只在本机：input_log.jsonl，超过 2 MB 分卷压缩，
--       分卷保留 input_log_days 天（llm_rerank/input_log_days，默认 30），过期的在会话启动时删除。
-- 由 ai_rerank_processor 调用：init 时 attach(env)，每次按键 key(env, key)。状态存在 env 里，每个会话（输入框）独立。
--
-- 事件（每行一个 JSON；公共字段 v 格式版本、t 秒级时间戳、q 会话内序号、s 会话 id、k 类型）：
--   start  会话开始
--   sel    选词：in 拼音、w 所选词、i 所选位次、ty 类型、ai 重排开关、top 最终首位、orig 原首位、c 候选与各项分数（有重排时）
--          所选词不含汉字（英文单词等）时不记
--   cmt    上屏（取自 commit_history）：w 文字、ty 类型。只记含汉字的上屏与纯标点（句末标点用于断句）；
--          含字母或数字而没有汉字的上屏（英文、数字串、回车上屏的拼音）不记内容，只记一次 cut（why = ascii）
--   del    没有未上屏拼音时的退格
--   cut    失步：why 为原因码（nav 方向键等、enter 回车、mod 组合键、delete 向后删除、ascii 英文模式直接输入）
local json = require("rerank_json")

local M = {}

local NAV = { Left = true, Right = true, Up = true, Down = true, Home = true, End = true,
              Page_Up = true, Page_Down = true, Tab = true }
local HARMLESS_MOD = { ["Control+c"] = true, ["Super+c"] = true, ["Control+C"] = true, ["Super+C"] = true }

local function has_han(s)
    for _, cp in utf8.codes(s) do
        if (cp >= 0x3400 and cp <= 0x9FFF) or (cp >= 0xF900 and cp <= 0xFAFF) or (cp >= 0x20000 and cp <= 0x2FFFF) then
            return true
        end
    end
    return false
end

-- 不含汉字时，含字母或数字（含全角）就不记内容
local function is_alnum_only(s)
    return not has_han(s) and (s:find("%w") ~= nil or s:find("\239\188[\144-\153\161-\186]") ~= nil
                                or s:find("\239\189[\129-\154]") ~= nil)
end

local function user_dir()
    local ok, ud = pcall(function() return rime_api.get_user_data_dir() end)
    if ok and ud and ud ~= "" then return ud end
    return nil
end

local function exists(path)
    local f = io.open(path, "r")
    if f then f:close(); return true end
    return false
end

-- 分卷索引：记录每个分卷的文件名与创建时间，用来删除过期分卷（Lua 没有可移植的目录遍历）
local function prune(index_path, days)
    local keep, now = {}, os.time()
    local f = io.open(index_path, "r")
    if not f then return end
    for line in f:lines() do
        local ts, name = line:match("^(%d+)\t(.+)$")
        if ts then
            if now - tonumber(ts) > days * 86400 then
                os.remove(name); os.remove(name .. ".gz")
            else
                keep[#keep + 1] = line
            end
        end
    end
    f:close()
    f = io.open(index_path, "w")
    if f then
        if #keep > 0 then f:write(table.concat(keep, "\n"), "\n") end
        f:close()
    end
end

local function write(st, ev)
    st.q = st.q + 1
    ev.v, ev.t, ev.q, ev.s = 1, os.time(), st.q, st.sid
    local f = io.open(st.path, "a")
    if not f then return end
    local size = f:seek("end")
    f:write(json.encode(ev), "\n")
    f:close()
    if size and size > 2 * 1024 * 1024 then
        local ok, arc = pcall(require, "corpus_archive")
        local rot = ok and arc.rotate(st.path)
        if rot then
            local idx = io.open(st.index, "a")
            if idx then idx:write(os.time(), "\t", rot, "\n"); idx:close() end
        end
    end
end

-- 把 commit_history 里新出现的上屏记为 cmt。commit_history 有长度上限（满了以后长度不变），
-- 所以不按长度而按“上次快照的末尾与这次的开头重叠多少”找出新增部分。
local function flush_commits(st, ctx, quiet)
    local ok, all = pcall(function() return ctx.commit_history:to_table() end)
    if not ok or type(all) ~= "table" then return end
    local cur = {}
    for i, r in ipairs(all) do cur[i] = { text = r.text or "", type = r.type or "" } end
    local prev = st.snap
    local overlap = 0
    for len = math.min(#prev, #cur), 1, -1 do
        local match = true
        for j = 1, len do
            local a, b = prev[#prev - len + j], cur[j]
            if a.text ~= b.text or a.type ~= b.type then match = false; break end
        end
        if match then overlap = len; break end
    end
    -- 快照非空却完全不重叠：多为引擎清空了历史，新历史全部算新增。quiet：只建立快照，不记录
    for i = quiet and #cur + 1 or overlap + 1, #cur do
        local text = cur[i].text
        local okh, alnum = pcall(is_alnum_only, text)
        if text == "" then
            -- 空上屏：忽略
        elseif not okh or alnum then
            if not st.ascii then
                st.ascii = true
                write(st, { k = "cut", why = "ascii" })
            end
        else
            st.ascii = false
            write(st, { k = "cmt", w = text, ty = cur[i].type })
        end
    end
    st.snap = cur
end

local function classify(repr)
    if repr == "BackSpace" then return "del" end
    if repr == "Delete" then return "cut", "delete" end
    if repr == "Return" or repr == "KP_Enter" then return "cut", "enter" end
    if NAV[repr] then return "cut", "nav" end
    local mod, rest = repr:match("^(%a+)%+(.+)$")
    if mod and (mod == "Control" or mod == "Super" or mod == "Alt" or mod == "Shift") then
        if NAV[rest] or rest == "Return" then return "cut", "nav" end
        if mod ~= "Shift" and not HARMLESS_MOD[repr] then return "cut", "mod" end
    end
    return nil
end

function M.attach(env)
    local ud = user_dir()
    if not ud or not exists(ud .. "/input_log.enabled") then return end
    local sc = env.engine.schema.config
    local days = 30
    pcall(function() days = sc:get_int("llm_rerank/input_log_days") or 30 end)
    local st = {
        path = ud .. "/input_log.jsonl", index = ud .. "/input_log.parts",
        sid = string.format("%x%04x", os.time(), math.random(0, 0xffff)), q = 0, snap = {}, ascii = false,
    }
    pcall(prune, st.index, days)
    env.input_log = st
    _G.ai_select_stats_on = true   -- 过滤器据此整理候选与各项分数（sel 事件的 c 字段）
    local ctx = env.engine.context
    pcall(flush_commits, st, ctx, true)   -- 建立快照：会话开始前的历史不会被当成新上屏
    write(st, { k = "start" })

    ctx.select_notifier:connect(function(c)
        pcall(function()
            flush_commits(st, c)
            local cand = c:get_selected_candidate()
            if not cand or not has_han(cand.text or "") then return end
            local seg = c.composition:back()
            local input = c.input or ""
            local info = _G.ai_select_info
            local fresh = info and info.input == input
            write(st, {
                k = "sel", ["in"] = input, w = cand.text, ty = cand.type,
                i = (seg and seg.selected_index or 0) + 1,
                ai = c:get_option("ai_rerank") and 1 or 0,
                top = fresh and info.ft or nil, orig = fresh and info.of or nil,
                c = fresh and info.rp and info.rp.nat and info.rp or nil,
            })
        end)
    end)
    ctx.commit_notifier:connect(function(c) pcall(flush_commits, st, c) end)
end

-- 每次按键调用（按键抬起不调用）。先记下此前的上屏，再判断这个键是否删除或失步。
function M.key(env, key)
    local st = env.input_log
    if not st then return end
    local ctx = env.engine.context
    pcall(flush_commits, st, ctx)
    if (ctx.input or "") ~= "" then return end   -- 有未上屏的拼音：按键作用于拼音，与已上屏的文字无关
    local repr = key:repr()
    local kind, why = classify(repr)
    if kind == "del" then
        write(st, { k = "del" })
    elseif kind == "cut" then
        write(st, { k = "cut", why = why })
    elseif not st.ascii and ctx:get_option("ascii_mode") and (#repr == 1 or repr == "space") then
        st.ascii = true            -- 英文模式下直接输入的字母不经过 Rime 上屏，内容未知：记一次失步
        write(st, { k = "cut", why = "ascii" })
    end
end

return M
