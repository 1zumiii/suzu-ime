-- ai_rerank_app_ctx.lua — 把应用里光标前的文字接到共用的 ai_rerank_* Lua 上（Android 与 Windows 共用的接入层）。
-- 共用的 ai_rerank_processor 只知道本会话上屏的词；客户端库还能给出应用里光标前的真文字（llm_context，读不到返回 nil）：
--   Android：改版 Trime 用 InputConnection.getTextBeforeCursor 读，经 JNI 写进 librime_llm.so（密码框、无痕模式不读）；
--   Windows：rime_llm.dll 经 UIA / COM 读，另有 kick_context、edit_reset_context、fg_changed、click_happened。
-- 没有的接口按“没发生”处理。本文件不改共用 Lua，只做三件事：
--   1) 把 _G.ai_rerank_context_get 换成“先读应用文字、读不到退回上屏历史”的版本（净化后再给 filter 与预解码）；
--   2) 点击鼠标、切换前台应用时，用 ai_rerank_processor 现成的运行时选项 ai_ctx_reset 清空它的上屏历史；
--      退格、方向键等编辑键、点击之后让快照失效并延迟重读；上屏后立刻重读（kick）。
--      Android 的光标移动由 Trime 自己置 ai_ctx_reset，快照在每次上屏、编辑后由 Trime 刷新；
--   3) 每次按键按“filter 实际会用的上文”补一次预解码（processor 自己只会按上屏历史预解码）。
-- 位置：engine/processors 里放在 ai_rerank_processor 之后（预解码要以本文件的为准）。
-- 配置：llm_rerank/context_source: auto（默认）| history（只用上屏历史，排查用）。
-- 隐私：不写任何文件；读到的文字只在内存里，不记录。
-- Windows 也挂载本文件（原 Windows 专用的 ai_rerank_win_ctx.lua 与本文件代码相同，已删除）。

-- 上文净化（与旧 llm_processor 相同）：UIA/COM 读到的文字可能夹着路径、代码、网址、界面文字，
-- 字符级学生模型只在中文句子上训练，这样的上文会把重排带偏。从光标处往回取，遇到明显不是自然语言的字符
-- （路径、代码符号）或过长的英文串就截断；截完不足 2 个字按没有上文处理（不重排）。
local BAD_CTX_CHARS = { ["\\"] = true, ["/"] = true, ["_"] = true, ["="] = true, ["<"] = true, [">"] = true,
                        ["{"] = true, ["}"] = true, ["["] = true, ["]"] = true, ["|"] = true, ["`"] = true,
                        ["@"] = true, ["#"] = true, ["^"] = true, ["~"] = true, ["$"] = true, ["%"] = true,
                        ["*"] = true, ["+"] = true, ["&"] = true }
local MAX_ASCII_RUN = 10
local function clean_ctx(t)
    if not t or t == "" then return "" end
    local chars = {}
    for _, cp in utf8.codes(t) do chars[#chars + 1] = utf8.char(cp) end
    local out, run, hit_bad = {}, 0, false
    for i = #chars, math.max(1, #chars - 80), -1 do
        local c = chars[i]
        if BAD_CTX_CHARS[c] then hit_bad = true; break end
        if #c == 1 and c:match("[%w]") then
            run = run + 1
            if run > MAX_ASCII_RUN then
                for _ = 1, run - 1 do table.remove(out) end
                break
            end
        else
            run = 0
        end
        out[#out + 1] = c
    end
    local res = {}
    for i = #out, 1, -1 do res[#res + 1] = out[i] end
    local str = table.concat(res)
    if hit_bad then str = str:gsub("^%w+", "") end
    if (utf8.len(str) or 0) < 2 then return "" end
    return str
end

local NAV_KEYS = { Left = true, Right = true, Up = true, Down = true,
                   Home = true, End = true, Page_Up = true, Page_Down = true }
local function is_edit_key(k)
    if k == "BackSpace" or k == "Delete" or k == "Return" or k == "KP_Enter"
       or k == "Control+BackSpace" or k == "Control+Delete"
       or k:match("^Control%+[zyvxZYVX]$") then
        return true
    end
    if NAV_KEYS[k] then return true end
    for name in pairs(NAV_KEYS) do
        if #k > #name and k:sub(-#name) == name then return true end
    end
    return false
end

local history_mode = false      -- llm_rerank/context_source = history
local base_get = nil            -- 共用 processor 原来的上文函数
local wrapped = nil             -- 本文件换上去的版本
local last_prep = nil
local kick_hooked = false

local function ctx_text()
    local llm = _G.ai_rerank_llm
    if not history_mode and llm and llm.llm_context then
        local ok, t = pcall(llm.llm_context)
        if ok and type(t) == "string" and #t > 0 then
            local c = clean_ctx((t:gsub("%s+", "")))
            if c ~= "" then return c end
        end
    end
    return clean_ctx(base_get and base_get() or "")
end

-- 共用 processor 在第一次加载时才定义 _G.ai_rerank_context_get；每次按键检查一下，还没换就换上
local function ensure_wrapped()
    local cur = _G.ai_rerank_context_get
    if cur and cur ~= wrapped then
        base_get = cur
        wrapped = function() return ctx_text() end
        _G.ai_rerank_context_get = wrapped
    end
end

local function processor(key, env)
    if key:release() then return 2 end
    local ctx = env.engine.context
    ensure_wrapped()
    local llm = _G.ai_rerank_llm
    if not llm or not wrapped or not ctx:get_option("ai_rerank") then return 2 end

    if not kick_hooked and llm.kick_context then
        kick_hooked = true
        -- 上屏后立刻刷新光标前文快照（读链在后台线程，下一键即有新鲜上文）
        ctx.commit_notifier:connect(function() pcall(llm.kick_context) end)
    end

    -- 点击：光标可能移动，上屏历史不再代表光标前的内容；快照同样是旧时刻的，失效后重读
    if llm.click_happened and llm.click_happened() then
        ctx:set_option("ai_ctx_reset", true)       -- ai_rerank_processor 收到后清空历史与个人词对的“前一个词”
        if llm.edit_reset_context then pcall(llm.edit_reset_context) end
    end
    -- 切换到别的应用：旧窗口的历史不能冒充新窗口的上文（DLL 内部比前台进程号）
    if llm.fg_changed and llm.fg_changed() then
        ctx:set_option("ai_ctx_reset", true)
    end
    -- 编辑位置变化的键（退格、方向键、回车、撤销粘贴等，且没有未上屏编码）：快照失效并延迟重读
    if ctx.input == "" and is_edit_key(key:repr()) and llm.edit_reset_context then
        pcall(llm.edit_reset_context)
    end

    -- 预解码以 filter 实际会用的上文为准
    if llm.prepare then
        local c = ctx_text()
        if c ~= last_prep then
            if llm.prepare(c) then last_prep = c end
        end
        if llm.is_ready and not llm.is_ready() then last_prep = nil end
    end
    return 2
end

return setmetatable({
    init = function(env)
        local ok, src = pcall(function() return env.engine.schema.config:get_string("llm_rerank/context_source") end)
        history_mode = ok and src == "history"
    end,
    func = processor,
}, { __call = function(_, key, env) return processor(key, env) end })
