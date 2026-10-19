-- 输入事件流的测试：模拟 Rime 环境驱动 ai_rerank_processor（不需要模型），检查 input_log.jsonl 的事件，
-- 再用 tools/input_log.py 重放，检查推导出的句子、纠错、词对。
-- 用法：lua input_log_test.lua <仓库目录> <临时目录>
local repo, ud = arg[1], arg[2]
package.path = repo .. "/rime/lua/?.lua;" .. package.path
local fails = 0
local function check(name, cond, extra)
  print((cond and "PASS " or "FAIL ") .. name .. (extra and ("  [" .. extra .. "]") or ""))
  if not cond then fails = fails + 1 end
end
os.execute("rm -rf '" .. ud .. "' && mkdir -p '" .. ud .. "'")
rime_api = { get_user_data_dir = function() return ud end, get_distribution_code_name = function() return "Squirrel" end }

local hist = { "旧" }                 -- 会话开始前的历史：不应被记录
local opts = { ai_rerank = false, ascii_mode = false }
local sel_cbs, cmt_cbs = {}, {}
local ctx
ctx = {
  input = "",
  commit_history = {
    to_table = function() local t = {}; for i, h in ipairs(hist) do t[i] = { text = h[1] or h, type = h[2] or "phrase" } end; return t end,
    back = function() local h = hist[#hist]; return h and { text = h[1] or h, type = h[2] or "phrase" } end,
  },
  composition = { back = function() return { selected_index = ctx._sel or 0 } end },
  get_option = function(_, n) return opts[n] end,
  set_option = function() end,
  get_selected_candidate = function() return ctx._cand end,
  select_notifier = { connect = function(_, f) sel_cbs[#sel_cbs + 1] = f end },
  commit_notifier = { connect = function(_, f) cmt_cbs[#cmt_cbs + 1] = f end },
  option_update_notifier = { connect = function() end },
}
local env = { engine = { context = ctx, schema = { config = {
  get_string = function() return nil end, get_int = function() return nil end,
  get_bool = function() return nil end, get_double = function() return nil end,
} } } }

local function run(enabled)
  if enabled then io.open(ud .. "/input_log.enabled", "w"):close() end
  local processor = require("ai_rerank_processor")
  processor.init(env)
  return processor
end

-- 1) 没有开关文件：不记录
package.loaded["ai_rerank_processor"], package.loaded["input_log"] = nil, nil
local p = run(false)
p({ release = function() return false end, repr = function() return "BackSpace" end }, env)
check("没有 input_log.enabled 时不写文件", io.open(ud .. "/input_log.jsonl", "r") == nil)

-- 2) 打开开关，新会话
package.loaded["ai_rerank_processor"], package.loaded["input_log"] = nil, nil
sel_cbs, cmt_cbs = {}, {}
p = run(true)
local function key(repr, input)
  ctx.input = input or ""
  p({ release = function() return false end, repr = function() return repr end }, env)
end
local function select(input, text, idx)
  ctx.input, ctx._cand, ctx._sel = input, { text = text, type = "phrase" }, (idx or 1) - 1
  for _, f in ipairs(sel_cbs) do f(ctx) end
end
local function commit(text, ty)
  hist[#hist + 1] = { text, ty or "phrase" }
  if #hist > 20 then table.remove(hist, 1) end   -- 与 librime 一样有长度上限
  ctx.input = ""
  for _, f in ipairs(cmt_cbs) do f(ctx) end
end

-- 句子 1：选错“经理”，删掉重打“精力”，然后“很好。”
select("jingli", "经理", 1); commit("经理")
key("BackSpace"); key("BackSpace")
select("jingli", "精力", 2); commit("精力")
select("hen", "很"); commit("很")
select("hao", "好"); commit("好")
commit("。", "punct")
-- 句子 2：改了说法（今天 → 明天），然后方向键
select("jintian", "今天"); commit("今天")
key("BackSpace"); key("BackSpace")
select("mingtian", "明天"); commit("明天")
key("Left")
-- 句子 3：栈已空时退格（失步），之后的句子不完整
key("BackSpace"); key("BackSpace"); key("BackSpace")
select("ni", "你"); commit("你"); commit("。", "punct")
-- 英文模式直接输入、组合键
opts.ascii_mode = true; key("a"); key("b"); opts.ascii_mode = false
key("Control+v")
-- 有拼音时的退格不记录
key("BackSpace", "jin")
-- 句子 4：一次上屏由两次选词组成
select("jingli", "精力"); select("guolai", "过来"); commit("精力过来"); commit("。", "punct")
-- 英文、数字的上屏只记一次失步，不记内容；英文候选的选词不记
select("iphone", "iPhone"); commit("iPhone"); commit("12345", "raw")

-- ---------- 事件 ----------
local evs, raw = {}, {}
for ln in io.lines(ud .. "/input_log.jsonl") do
  raw[#raw + 1] = ln
  evs[#evs + 1] = { k = ln:match('"k":"(%a+)"'), why = ln:match('"why":"(%a+)"') }
end
local n = {}
for _, e in ipairs(evs) do n[e.k] = (n[e.k] or 0) + 1 end
check("会话开始前的历史不记录", not table.concat(raw, "\n"):find("旧"))
check("英文、数字不记内容", not table.concat(raw, "\n"):find("iPhone") and not table.concat(raw, "\n"):find("12345"))
check("事件计数", n.start == 1 and n.sel == 9 and n.cmt == 11 and n.del == 7 and n.cut == 4,
  string.format("start=%s sel=%s cmt=%s del=%s cut=%s", n.start, n.sel, n.cmt, n.del, n.cut))
local whys = {}
for _, e in ipairs(evs) do if e.k == "cut" then whys[#whys + 1] = e.why end end
check("失步原因：nav、ascii（只记一次）、mod、ascii（英文与数字上屏合记一次）", table.concat(whys, ",") == "nav,ascii,mod,ascii", table.concat(whys, ","))

-- ---------- 重放 ----------
local outdir = ud .. "/derived"
local pipe = io.popen("python3 -I '" .. repo .. "/tools/input_log.py' '" .. ud .. "' --export '" .. outdir .. "' 2>&1")
local out = pipe:read("a"); pipe:close()
local function num(label) return tonumber(out:match(label .. "%s+(%d+)")) end
check("输入法选错 1、改了说法 1、重打同一个词 0", num("输入法选错") == 1 and num("改了说法") == 1 and not out:find("重打同一个词"))
check("完整句子 3、不完整 1", num("句子（完整）") == 3 and num("句子（不完整）") == 1, out:match("句子（完整）[^\n]*"))
check("删除下溢 3", num("删除下溢（失步）") == 3)
local function read(name) local f = io.open(outdir .. "/" .. name); local s = f and f:read("a") or ""; if f then f:close() end; return s end
local sent = read("sentences.tsv")
check("句子内容", sent:find("\t精力很好。\n") and sent:find("\t明天\n") and sent:find("\t精力过来。\n")
  and not sent:find("经理") and not sent:find("你。"))
check("纠错：jingli 经理 → 精力", read("corrections.tsv") == "jingli\t\t经理\t精力\n", read("corrections.tsv"))
local pairs_s = read("pairs.tsv")
check("词对：精力→很、很→好、精力→过来", pairs_s:find("精力\t很\t1") and pairs_s:find("很\t好\t1") and pairs_s:find("精力\t过来\t1"))
check("汇总输出不含文字", not out:find("精力") and not out:find("jingli"))

if fails > 0 then print(fails .. " FAILED"); os.exit(1) end
print("ALL PASS")
os.exit(0)
