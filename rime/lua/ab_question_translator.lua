local M = {}
local negations = { { "mei", "没" }, { "bu", "不" } }
local max_candidates, max_scan = 2, 32

-- 正反问句和实验性否定候选只从常用谓语生成。词典里的同音名词和生僻词往往也能
-- 覆盖整个输入，因此不能只凭词频或候选顺序判断。
local predicates = {}
for word in ([[
    安排 安装 保存 保留 报告 报名 帮助 搬家 备份 比较 毕业 表达 表示 播放 补充 参加 测试 查询
    查找 撤回 成功 成立 吃饭 出发 出门 处理 传播 创建 打包 打开 打算 打印 达到 达成 答应 担心
    到达 登录 登记 删除 导出 导入 得到 调整 调用 掉线 订阅 定位 断开 对齐 读取 发送 发现
    发货 发言 发布 放弃 放行 放心 方便 反馈 返回 反对 分析 分享 复制 复习 复核 改变 改进
    改善 改正 感觉 感谢 干净 告诉 更换 更新 工作 沟通 购买 关闭 关机 关注 观察 管理
    过来 过去 验证 验收 合并 合适 合作 好看 好听 好吃 好用 恢复 回来 回去 回答 回应
    活动 获取 检查 检测 解决 解除 接受 接收 接触 接入 介绍 解释 结束 结婚 结算 记录
    记得 建设 建立 建议 见过 见面 健康 简单 减少 坚持 讲清 交付 交换 校对 矫正 叫醒
    继续 计划 计算 进步 进来 进去 进行 决定 开启 开始 开发 开通 开心 看见 看到 考虑
    考试 控制 困难 开会 快速 理解 连接 联系 领取 留下 浏览 满意 明白 明确 免费
    美化 弥补 能用 挪动 拍照 判断 批准 匹配 平衡 评价 启动 签字 签署 清除 清理 清楚
    确定 确认 请求 取消 区分 取出 取得 去过 热闹 认真 认识 认同 容易 融合 审核 审批
    上报 上班 上课 上传 设置 申请 生效 生成 失败 失效 使用 实现 适合 收到 收集 收拾
    熟悉 说明 睡觉 输入 输出 送达 搜索 存储 随意 提出 提交 提供 提高 提醒 添加 填写
    通知 通过 同意 同步 讨论 退出 退回 完成 完善 忘记 维护 问好 稳定 喜欢 洗澡 下载
    下班 下课 选中 选择 显示 相信 相同 想到 想起 想念 写入 写完 修改 修复 休息
    学会 学习 需要 允许 询问 压缩 延迟 严重 演示 邀请 要求 移动 移除 应用 影响 优化
    预约 预测 阅读 运行 赞成 粘贴 咨询 支持 执行 知道 指出 指定 指导 制作 制定
    重要 重启 重试 重置 注册 注意 准备 准时 转发 转交 转换 转移 组织 做完
]]):gmatch("%S+") do
    predicates[word] = true
end

local function query(env, input)
    local segment = Segment(0, #input)
    segment.tags = Set({ "abc" })
    return env.translator:query(input, segment)
end

-- 反查每个字，排除翻译器因简拼、模糊音或补全而返回的词。
local function matches_spelling(env, text, input, first)
    local positions = { [1] = true }
    local count = 0
    for _, cp in utf8.codes(text) do
        if cp < 0x4e00 or cp > 0x9fff then return false end
        count = count + 1
        local next_positions = {}
        for reading in (env.reverse:lookup(utf8.char(cp)) or ""):gmatch("[a-z]+") do
            if count ~= 1 or not first or reading == first then
                for start in pairs(positions) do
                    local finish = start + #reading
                    if input:sub(start, finish - 1) == reading then next_positions[finish] = true end
                end
            end
        end
        if not next(next_positions) then return false end
        positions = next_positions
    end
    return count >= (first and 2 or 1) and positions[#input + 1] == true
end

local function lookup(env, input, first, negation, out, seen)
    local translation = query(env, input)
    if not translation then return end
    local scanned = 0
    for cand in translation:iter() do
        scanned = scanned + 1
        if cand._end < #input or scanned > max_scan then break end
        if cand._end == #input and (cand.type == "phrase" or cand.type == "user_phrase")
            and predicates[cand.text]
            and matches_spelling(env, cand.text, input, first) then
            local head = first and cand.text:sub(1, utf8.offset(cand.text, 2) - 1) or ""
            local text = head .. negation .. cand.text
            if not seen[text] then
                out[#out + 1] = text
                seen[text] = true
                if #out == max_candidates then return end
            end
        end
    end
end

function M.init(env)
    local schema = env.engine.schema
    local sc = schema.config
    local id = schema.schema_id
    env.question = sc:get_bool("ab_question/enabled")
    if env.question == nil then env.question = id == "rime_ice" or id == "suzu" or id == "luna_pinyin" end
    env.negated = sc:get_bool("ab_question/negated_word") == true
    local dictionary = sc:get_string("translator/dictionary")
    if (env.question or env.negated) and dictionary and not id:find("double_pinyin", 1, true) then
        pcall(function()
            env.translator = Component.Translator(env.engine, "", "script_translator@translator")
            env.reverse = ReverseDb("build/" .. dictionary .. ".reverse.bin")
        end)
    end
end

function M.func(input, segment, env)
    if not env.reverse or not env.translator or not segment:has_tag("abc")
        or #input > 30 or not input:match("^[a-z]+$") then return end
    local out, seen = {}, {}
    for _, negation in ipairs(negations) do
        local spelling, word = negation[1], negation[2]
        if env.question then
            local at = input:find(spelling, 2, true)
            while at and at <= 7 do
                local first = input:sub(1, at - 1)
                local rest = input:sub(at + #spelling)
                if #rest > #first and rest:sub(1, #first) == first then
                    lookup(env, rest, first, word, out, seen)
                    if #out == max_candidates then break end
                end
                at = input:find(spelling, at + 1, true)
            end
        end
        if #out == max_candidates then break end
        if env.negated and input:sub(1, #spelling) == spelling and #input > #spelling then
            lookup(env, input:sub(#spelling + 1), nil, word, out, seen)
            if #out == max_candidates then break end
        end
    end
    if #out == 0 then return end

    local normal = query(env, input)
    if not normal then return end
    local quality
    local scanned = 0
    for cand in normal:iter() do
        if cand._end ~= #input or scanned >= 10 then break end
        if quality == nil then quality = cand.quality end
        seen[cand.text] = false
        scanned = scanned + 1
    end
    if quality == nil then return end
    for _, text in ipairs(out) do
        if seen[text] then
            local cand = Candidate("ab_question", segment.start, segment._end, text, "")
            cand.quality = quality - 0.000001
            yield(cand)
        end
    end
end

return M
