// 打分结果缓存：键为“上文编号序列 + 候选文字”，值为各候选的分数。容量小、最近最少使用淘汰。
// 作用：同一组候选被反复请求时（翻页、候选窗重建、超时后的下一次按键）直接返回，不再排队。
// 非线程安全，由 Engine 在自己的锁内使用。
#pragma once

#include <list>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "model/vocab.h"

namespace suzu {

class ResultCache {
public:
    explicit ResultCache(size_t capacity = 64) : cap_(capacity) {}

    static std::string make_key(const TokenSeq & ctx, const std::vector<std::string> & cands) {
        std::string k;
        k.reserve(ctx.size() * 3 + cands.size() * 8);
        for (Token t : ctx) { k += std::to_string(t); k.push_back(','); }
        for (auto & c : cands) { k.push_back('\x1f'); k += c; }
        return k;
    }

    bool get(const std::string & key, std::vector<double> * out) {
        auto it = map_.find(key);
        if (it == map_.end()) return false;
        order_.splice(order_.begin(), order_, it->second.second);
        *out = it->second.first;
        return true;
    }

    void put(const std::string & key, std::vector<double> scores) {
        auto it = map_.find(key);
        if (it != map_.end()) {
            it->second.first = std::move(scores);
            order_.splice(order_.begin(), order_, it->second.second);
            return;
        }
        order_.push_front(key);
        map_.emplace(key, std::make_pair(std::move(scores), order_.begin()));
        while (map_.size() > cap_) {
            map_.erase(order_.back());
            order_.pop_back();
        }
    }

    void clear() { map_.clear(); order_.clear(); }

private:
    size_t cap_;
    std::list<std::string> order_;
    std::unordered_map<std::string, std::pair<std::vector<double>, std::list<std::string>::iterator>> map_;
};

}  // namespace suzu
