-- rank_unified.lua — 统一排序的打分（纯计算，不访问 Rime 与模型）
-- 设计见私有文档 ime/2026-10-20-input-event-log-and-unified-ranking.md 第 3 节。由 ai_rerank_filter 在
-- llm_rerank/ranker: unified 时调用；tools/replay.py 用同样的公式离线重算，两边须同步修改。
--
--   总分 = −alpha·ln(r) + beta·ln(1+dw) + λ·(s − s_max) + U − 模糊音扣分
--   r      候选在打分组内的 Rime 原名次（最近使用提升之前），1 起
--   s      模型分；λ 为 0 时不参与。模型在用而某候选无效分（如含词表外字）时，总分为 −∞
--   dw     词在词典里的权重（词频），核心的 word_freq 给出；单字、四字以上等不在索引里的取 dw_unknown
--   U      fb·ln(1+会话词频) + pg·ln(1+词对) + cg·ln(1+纠错记忆) + mu·最近用过
--   λ      上文不足 2 个字或不用模型时为 0；随上文字数线性增长，lam_ctx_full 个字时达到 lam_max；
--          输入只有 1～2 个字母时再乘 lam_short
-- 首位门控（top_gate，默认 0 即关闭）：模型把首位换成别的词时，新首位的模型分要比“不用模型时的首位”高出 top_gate
-- 以上才换，否则不用模型时的首位留在第一位，其余位置照新顺序。真实使用里分差不到 3 时 AI 改首位约一半是错的
-- （用户顺手接受后又删掉改回），见私有文档 ime/2026-10-22-ai-top-gate.md。
-- 关 AI 等价于 λ = 0：开与关只差 λ·模型分 这一项。
local M = {}

M.defaults = {
    alpha = 1.0, beta = 0, dw_unknown = 3000, mu = 0.6, fb = 0, pg = 1.5, cg = 3.0, fp = 3.0,
    lam_max = 1.0, lam_ctx_full = 4, lam_short = 0.5, top_gate = 0,
}

-- 读 llm_rerank/unified/<名字>，没写的取默认值
function M.load(sc)
    local p = {}
    for k, v in pairs(M.defaults) do
        local ok, x = pcall(function() return sc:get_double("llm_rerank/unified/" .. k) end)
        p[k] = (ok and type(x) == "number" and x == x) and x or v
    end
    return p
end

function M.lam(p, ctx_chars, input_len, use_model)
    if not use_model or ctx_chars < 2 then return 0 end
    local l = p.lam_max * math.min(1, (ctx_chars - 1) / math.max(1, p.lam_ctx_full - 1))
    if input_len <= 2 then l = l * p.lam_short end
    return l
end

-- f：一个候选的特征（ai_rerank_filter 的 replay_cands 给出的表）
function M.score(f, p, lam, smax)
    if f.wd == 1 then return -math.huge end
    local t = -p.alpha * math.log(f.r)
    if p.beta ~= 0 then t = t + p.beta * math.log(1 + (f.dw or p.dw_unknown)) end
    if lam > 0 then
        if not f.s then return -math.huge end
        t = t + lam * (f.s - smax)
    end
    t = t + p.fb * math.log(1 + (f.fq or 0)) + p.pg * math.log(1 + (f.pr or 0))
          + p.cg * math.log(1 + (f.ch or 0)) + p.mu * (f.rec or 0)
    if f.fz == 1 then t = t - p.fp end
    return t
end

-- 返回按总分降序排好的特征表（同分按 Rime 原名次），以及各自的总分
function M.order(feats, p, lam)
    local smax = -math.huge
    for _, f in ipairs(feats) do if f.s and f.s > smax then smax = f.s end end
    local arr = {}
    for i, f in ipairs(feats) do arr[i] = { f = f, t = M.score(f, p, lam, smax) } end
    table.sort(arr, function(a, b)
        if a.t ~= b.t then return a.t > b.t end
        return a.f.r < b.f.r
    end)
    local out, tot = {}, {}
    for i, a in ipairs(arr) do out[i] = a.f; tot[i] = a.t end
    return out, tot
end

-- 首位门控：sorted 为 order(feats, p, lam) 的结果，base 为不用模型时的首位（order(feats, p, 0)[1]）。
-- 需要时把 base 移回第一位（原地修改 sorted），返回是否动过
function M.gate_top(sorted, base, p, lam)
    if lam <= 0 or (p.top_gate or 0) <= 0 or sorted[1] == base then return false end
    local s1, s0 = sorted[1].s, base.s
    if s1 and s0 and s1 - s0 >= p.top_gate then return false end
    for i = 2, #sorted do
        if sorted[i] == base then
            table.remove(sorted, i)
            table.insert(sorted, 1, base)
            return true
        end
    end
    return false
end

-- 第二阶段拼出的句子 x 与 Rime 的整句 n 比较：n 视为第 1 名、x 视为第 2 名，只比模型分与名次先验
function M.stage2_wins(p, lam, s_n, s_x)
    return lam > 0 and s_n ~= nil and s_x ~= nil and lam * (s_x - s_n) > p.alpha * math.log(2)
end

return M
