-- rerank_json.lua — 本机记录文件用的最小 JSON 编码（ai_rerank_processor、input_log 共用）
local M = {}

-- 只处理本机记录用到的数据（字符串、数字、布尔、表；表按是否为连续数组区分）
function M.encode(v)
    local tv = type(v)
    if tv == "string" then
        return '"' .. v:gsub('[%c"\\]', function(ch)
            local map = { ['"'] = '\\"', ['\\'] = '\\\\', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }
            return map[ch] or string.format("\\u%04x", ch:byte())
        end) .. '"'
    elseif tv == "number" then
        if v ~= v or v == math.huge or v == -math.huge then return "null" end
        if math.type(v) == "integer" then return tostring(v) end
        return string.format("%.6g", v)
    elseif tv == "boolean" then
        return tostring(v)
    elseif tv == "table" then
        local n = #v
        local is_arr = n > 0 or next(v) == nil
        if is_arr then for k in pairs(v) do if type(k) ~= "number" then is_arr = false; break end end end
        local parts = {}
        if is_arr then
            for i = 1, n do parts[i] = M.encode(v[i]) end
            return "[" .. table.concat(parts, ",") .. "]"
        end
        local keys = {}
        for k in pairs(v) do keys[#keys + 1] = tostring(k) end
        table.sort(keys)
        for _, k in ipairs(keys) do parts[#parts + 1] = M.encode(k) .. ":" .. M.encode(v[k]) end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    return "null"
end

return M
