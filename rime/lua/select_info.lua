-- select_info.lua — 在选词通知里取“这一次选了什么”：所选文字、类型、拼音、位次，以及过滤器留下的选词信息
-- 用空格、数字键或点击把整段输入一次上屏时，librime 先上屏并清空组合，再通知选词：
-- 此时 ctx:get_selected_candidate() 为 nil，ctx.input 也已是空串（Mac 实测：cand=nil input=[] info=jingli）。
-- 这时用过滤器留下的 _G.ai_select_info（拼音、各候选位次）和刚上屏的文字（commit_history 的最后一条）补全。
-- 补全有三道限制：每个使用方（tag）只补一次；信息超过 FRESH_SEC 秒不用；上屏文字必须是当时的候选之一
-- （多段选词的最后一段：上屏文字以某个候选结尾）。
-- 使用方：ai_rerank_processor（选词统计与语料、纠错记忆）、input_log（sel 事件）。
local M = {}

local FRESH_SEC = 10

-- 返回 { text, type, input, sel（最终顺序里的位次，从 1 起）, info（与这次选词对应的过滤器信息，可能为 nil）,
--        whole（是否覆盖整个输入）, filled（是否为整段上屏时补全的）}；取不到时返回 nil
function M.resolve(ctx, tag)
    local info = _G.ai_select_info
    local cand = ctx:get_selected_candidate()   -- 出错时交给调用方的错误处理（诊断日志）
    if cand then
        local input = ctx.input or ""
        local seg = ctx.composition:back()
        local st, e = cand._start, cand._end
        local whole = type(e) ~= "number" or e - (type(st) == "number" and st or 0) >= #input
        local fresh = info and info.input == input
        return { text = cand.text or "", type = cand.type, input = input,
                 sel = (seg and seg.selected_index or 0) + 1, info = fresh and info or nil, whole = whole }
    end
    if not info or info["used_" .. tag] or (info.input or "") == "" then return nil end
    if not info.t or os.time() - info.t > FRESH_SEC then return nil end
    local okh, rec = pcall(function() return ctx.commit_history:back() end)
    if not okh or not rec or (rec.text or "") == "" then return nil end
    local text = rec.text
    local pos = info.f and info.f[text]
    if not pos and info.f then
        -- 多段选词的最后一段：上屏的是前几段与这一段拼起来的文字，取能作为结尾的最长候选
        local best = nil
        for w, p in pairs(info.f) do
            if #w < #text and text:sub(-#w) == w and (not best or #w > #best) then best, pos = w, p end
        end
        if best then text = best end
    end
    if not pos then return nil end
    info["used_" .. tag] = true
    return { text = text, type = rec.type, input = info.input, sel = pos, info = info,
             whole = text == rec.text, filled = true }
end

return M
