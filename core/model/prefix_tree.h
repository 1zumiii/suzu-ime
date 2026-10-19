// 候选前缀树：首字（或前几个字）相同的候选共享解码。
// 例：经理、经历、精力 → 根下两个子节点「经」「精」；「经」之后只需解码一次，就能同时给「理」「历」打分。
// 本层只描述结构，不做推理；推理层按层（深度）遍历它组织批次。
#pragma once

#include <vector>

#include "model/vocab.h"

namespace suzu {

struct PrefixNode {
    Token token = 0;
    int parent = -1;              // 根为 -1
    int depth = 0;                // 根为 0；深度 d 的节点对应候选的第 d 个字
    std::vector<int> children;
    std::vector<int> ends_here;   // 在此结束的候选下标（可能有重复的候选文字）
};

class PrefixTree {
public:
    // 每个候选最多取前 max_len 个字参与打分（旧核心为 12）。share = false 时不合并前缀（每个候选一条独立的链，用于对照测量）。
    PrefixTree(const std::vector<TokenSeq> & cands, int max_len, bool share = true);

    const std::vector<PrefixNode> & nodes() const { return nodes_; }
    int max_depth() const { return max_depth_; }
    // 需要解码（有子节点）且不是根的节点数，即候选部分实际送进模型的字数
    int decode_count() const;

private:
    std::vector<PrefixNode> nodes_;
    int max_depth_ = 0;
};

}  // namespace suzu
