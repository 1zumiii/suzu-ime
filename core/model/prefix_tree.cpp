#include "model/prefix_tree.h"

#include <algorithm>

namespace suzu {

PrefixTree::PrefixTree(const std::vector<TokenSeq> & cands, int max_len, bool share) {
    nodes_.emplace_back();   // 根
    for (int ci = 0; ci < (int)cands.size(); ci++) {
        const TokenSeq & seq = cands[ci];
        int len = std::min((int)seq.size(), max_len);
        int cur = 0;
        for (int d = 0; d < len; d++) {
            int next = -1;
            if (share)
                for (int ch : nodes_[cur].children)
                    if (nodes_[ch].token == seq[d]) { next = ch; break; }
            if (next < 0) {
                PrefixNode n;
                n.token = seq[d];
                n.parent = cur;
                n.depth = d + 1;
                next = (int)nodes_.size();
                nodes_.push_back(n);
                nodes_[cur].children.push_back(next);
            }
            cur = next;
        }
        nodes_[cur].ends_here.push_back(ci);
        max_depth_ = std::max(max_depth_, len);
    }
}

int PrefixTree::decode_count() const {
    int n = 0;
    for (size_t i = 1; i < nodes_.size(); i++)
        if (!nodes_[i].children.empty()) n++;
    return n;
}

}  // namespace suzu
