#include "lexicon/lexicon.h"

#include <algorithm>
#include <fstream>
#include <unordered_set>

namespace suzu {

namespace {

std::vector<std::string> split(const std::string & s, char sep) {
    std::vector<std::string> out;
    size_t b = 0;
    for (size_t i = 0; i <= s.size(); i++) {
        if (i == s.size() || s[i] == sep) {
            if (i > b) out.push_back(s.substr(b, i - b));
            b = i + 1;
        }
    }
    return out;
}

bool is_two_letter_initial(const std::string & s, size_t pos) {
    return pos + 1 < s.size() && s[pos + 1] == 'h' && (s[pos] == 'z' || s[pos] == 'c' || s[pos] == 's');
}

}  // namespace

bool Lexicon::load(const std::vector<std::string> & dict_files, const Vocab & vocab, int min_chars, int max_chars,
                   std::string * err) {
    min_chars_ = std::max(1, min_chars);
    max_chars_ = std::min(3, std::max(min_chars_, max_chars));
    entries_.clear();
    syllables_.clear();
    syllable_id_.clear();
    buckets_.clear();
    initial_ok_.assign(26, 0);
    max_syllable_len_ = 0;
    int files_read = 0;
    for (const auto & path : dict_files) {
        std::ifstream in(path, std::ios::binary);
        if (!in) continue;
        files_read++;
        std::string line;
        bool body = false;   // 词条在 YAML 头（以 ... 结束）之后
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!body) {
                if (line.rfind("...", 0) == 0) body = true;
                continue;
            }
            if (line.empty() || line[0] == '#') continue;
            auto f = split(line, '\t');
            if (f.size() < 2) continue;
            TokenSeq toks = vocab.encode(f[0]);
            if ((int)toks.size() < min_chars_ || (int)toks.size() > max_chars_) continue;
            bool oov = false;
            for (Token t : toks) if (t == kUnkToken || t <= kBosToken || t > 0xFFFF) oov = true;
            if (oov) continue;
            auto syl = split(f[1], ' ');
            if (syl.size() != toks.size()) continue;
            Entry e{};
            e.n = (uint8_t)toks.size();
            e.weight = f.size() >= 3 ? atoi(f[2].c_str()) : 0;
            std::string key;
            bool bad = false;
            for (size_t i = 0; i < toks.size(); i++) {
                const std::string & s = syl[i];
                if (s.empty() || s[0] < 'a' || s[0] > 'z') { bad = true; break; }
                auto it = syllable_id_.find(s);
                uint16_t id;
                if (it == syllable_id_.end()) {
                    id = (uint16_t)syllables_.size();
                    syllables_.push_back(s);
                    syllable_id_.emplace(s, id);
                    max_syllable_len_ = std::max(max_syllable_len_, s.size());
                    initial_ok_[(size_t)(s[0] - 'a')] = 1;
                } else {
                    id = it->second;
                }
                e.tok[i] = (uint16_t)toks[i];
                e.syl[i] = id;
                key.push_back(s[0]);
            }
            if (bad) continue;
            buckets_[key].push_back((uint32_t)entries_.size());
            entries_.push_back(e);
        }
    }
    if (files_read == 0 || entries_.empty()) {
        if (err) *err = "lexicon: no usable dictionary (" + std::to_string(files_read) + " files read)";
        entries_.clear();
        return false;
    }
    for (auto & kv : buckets_) {
        std::stable_sort(kv.second.begin(), kv.second.end(),
                         [this](uint32_t a, uint32_t b) { return entries_[a].weight > entries_[b].weight; });
        kv.second.shrink_to_fit();
    }
    entries_.shrink_to_fit();   // 逐条追加时数组按倍数扩容，收缩到实际大小（手机上内存紧）
    return true;
}

TokenSeq Lexicon::tokens(uint32_t e) const {
    const Entry & x = entries_[e];
    return TokenSeq(x.tok, x.tok + x.n);
}

bool Lexicon::unit_matches(const InputUnit & u, uint16_t syl) const {
    const std::string & s = syllables_[syl];
    if (!u.initial) return s == u.text;
    return s.compare(0, u.text.size(), u.text) == 0;
}

bool Lexicon::can_split_full(const std::string & s) const {
    // 动态规划：ok[i] 表示前 i 个字母能切成纯音节
    std::vector<char> ok(s.size() + 1, 0);
    ok[0] = 1;
    for (size_t i = 0; i < s.size(); i++) {
        if (!ok[i]) continue;
        for (size_t len = 1; len <= max_syllable_len_ && i + len <= s.size(); len++)
            if (syllable_id_.count(s.substr(i, len))) ok[i + len] = 1;
    }
    return ok[s.size()] != 0;
}

void Lexicon::segment_from(const std::string & s, size_t pos, Segmentation & cur, std::vector<Segmentation> * out,
                           int max_segs) const {
    if ((int)out->size() >= max_segs) return;
    if (pos == s.size()) {
        if (!cur.empty()) out->push_back(cur);
        return;
    }
    if ((int)cur.size() >= max_chars_) return;
    // 完整音节（长的先试）
    for (size_t len = std::min(max_syllable_len_, s.size() - pos); len >= 1; len--) {
        std::string sub = s.substr(pos, len);
        if (!syllable_id_.count(sub)) continue;
        cur.push_back({sub, false});
        segment_from(s, pos + len, cur, out, max_segs);
        cur.pop_back();
    }
    // 声母：zh、ch、sh，或单个首字母。单个字母本身也是音节时（如 a、e），作为声母时匹配以它开头的音节
    if (is_two_letter_initial(s, pos)) {
        cur.push_back({s.substr(pos, 2), true});
        segment_from(s, pos + 2, cur, out, max_segs);
        cur.pop_back();
    }
    char c = s[pos];
    if (c >= 'a' && c <= 'z' && initial_ok_[(size_t)(c - 'a')]) {
        cur.push_back({std::string(1, c), true});
        segment_from(s, pos + 1, cur, out, max_segs);
        cur.pop_back();
    }
}

std::vector<Segmentation> Lexicon::segment(const std::string & input, bool * full_pinyin, int max_segs) const {
    // 只认小写字母与隔音符（'）；隔音符处必须断开
    std::vector<Segmentation> out;
    if (full_pinyin) *full_pinyin = false;
    if (input.empty() || entries_.empty()) return out;
    for (char c : input)
        if (!((c >= 'a' && c <= 'z') || c == '\'')) return out;
    auto parts = split(input, '\'');
    if (parts.empty()) return out;
    if (full_pinyin) {
        bool all = true;
        for (auto & p : parts) if (!can_split_full(p)) { all = false; break; }
        *full_pinyin = all;
    }
    // 各段分别切分，再按段组合（单元总数受 max_chars 限制）
    std::vector<Segmentation> acc = {Segmentation{}};
    for (auto & p : parts) {
        // 一段内的单元数不超过 max_chars；最少单元数在各段组合之后再检查
        std::vector<Segmentation> mine;
        Segmentation cur;
        segment_from(p, 0, cur, &mine, max_segs);
        std::vector<Segmentation> next;
        for (auto & a : acc)
            for (auto & m : mine) {
                if ((int)(a.size() + m.size()) > max_chars_) continue;
                Segmentation x = a;
                x.insert(x.end(), m.begin(), m.end());
                next.push_back(std::move(x));
                if ((int)next.size() >= max_segs) break;
            }
        acc.swap(next);
        if (acc.empty()) return out;
    }
    for (auto & a : acc)
        if ((int)a.size() >= min_chars_) out.push_back(std::move(a));
    return out;
}

std::vector<uint32_t> Lexicon::match(const std::vector<Segmentation> & segs) const {
    std::vector<uint32_t> hits;
    for (const auto & seg : segs) {
        std::string key;
        for (const auto & u : seg) key.push_back(u.text[0]);
        auto it = buckets_.find(key);
        if (it == buckets_.end()) continue;
        for (uint32_t e : it->second) {
            const Entry & x = entries_[e];
            bool ok = true;
            for (size_t i = 0; i < seg.size() && ok; i++) ok = unit_matches(seg[i], x.syl[i]);
            if (ok) hits.push_back(e);
        }
    }
    // 按词频降序；同一个词（字的编号相同）只留词频最高的一条
    std::stable_sort(hits.begin(), hits.end(), [this](uint32_t a, uint32_t b) {
        return entries_[a].weight > entries_[b].weight;
    });
    std::vector<uint32_t> out;
    std::unordered_set<uint64_t> seen;
    for (uint32_t e : hits) {
        const Entry & x = entries_[e];
        uint64_t k = x.n;
        for (int i = 0; i < x.n; i++) k = k * 65536 + x.tok[i];
        if (seen.insert(k).second) out.push_back(e);
    }
    return out;
}

}  // namespace suzu
