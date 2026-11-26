local dir = assert(arg[1])
package.path = dir .. "/rime/lua/?.lua;" .. package.path
local translator = require("ab_question_translator")
local output, queries
local words = {
    jiemeijiejue = { { text = "姐妹解决", type = "sentence", quality = 0 } },
    jiejue = { { text = "孑孓", type = "phrase" }, { text = "戒绝", type = "phrase" },
        { text = "解决", type = "phrase" }, { text = "借据", type = "phrase" } },
    jiej = { { text = "解决", type = "phrase" } },
    youmeiyouhua = { { text = "有没有花", type = "sentence", quality = 0 } },
    youhua = { { text = "油画", type = "phrase" }, { text = "优化", type = "phrase" } },
    bugoutong = { { text = "不苟同", type = "phrase", quality = 1 } },
    goutong = { { text = "沟通", type = "phrase" }, { text = "苟同", type = "phrase" } },
}
local reverse = { ["解"] = "jie xie", ["决"] = "jue", ["借"] = "jie", ["据"] = "ju",
    ["孑"] = "jie", ["孓"] = "jue", ["戒"] = "jie", ["绝"] = "jue",
    ["油"] = "you", ["画"] = "hua", ["优"] = "you", ["化"] = "hua",
    ["沟"] = "gou", ["苟"] = "gou", ["通"] = "tong", ["同"] = "tong" }
function ReverseDb() return { lookup = function(_, text) return reverse[text] or "" end } end
function Set(items) return items end
function Segment(start, finish)
    return { start = start, _end = finish, has_tag = function(_, tag) return tag == "abc" end }
end
function Candidate(kind, start, finish, text)
    return { type = kind, start = start, _end = finish, text = text }
end
function yield(candidate) output[#output + 1] = candidate end
Component = {
    Translator = function()
        return { query = function(_, input)
            queries = queries + 1
            if not words[input] then return end
            return { iter = function()
                local index = 0
                return function()
                    index = index + 1
                    local word = words[input][index]
                    if word then return setmetatable({ _end = #input }, { __index = word }) end
                end
            end }
        end }
    end,
}

local function run(input, options, id)
    output, queries = {}, 0
    local config = { get_bool = function(_, key) return options[key] end,
        get_string = function() return "rime_ice" end }
    local env = { engine = { schema = { schema_id = id or "rime_ice", config = config } } }
    translator.init(env)
    translator.func(input, Segment(2, 2 + #input), env)
    return output, queries
end

local out, n = run("jiemeijiejue", {})
assert(#out == 1 and out[1].text == "解没解决")
assert(out[1].quality < 0 and out[1].start == 2 and out[1]._end == 14 and n == 2)
assert(#run("jiemeijiej", {}) == 0)
assert(#run("jiemeijiejue", { ["ab_question/enabled"] = false }) == 0)
assert(#run("jiemeijiejue", {}, "double_pinyin_flypy") == 0)
out = run("youmeiyouhua", {})
assert(#out == 1 and out[1].text == "优没优化")
out, n = run("bugoutong", {})
assert(#out == 0 and n == 0)
out = run("bugoutong", { ["ab_question/negated_word"] = true })
assert(#out == 1 and out[1].text == "不沟通" and out[1].quality < 1)
print("ALL OK")
