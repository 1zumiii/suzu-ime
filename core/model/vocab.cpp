#include "model/vocab.h"

#include <fstream>

namespace suzu {

int Vocab::utf8_len(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c >> 5) == 6) return 2;
    if ((c >> 4) == 14) return 3;
    if ((c >> 3) == 30) return 4;
    return 1;
}

bool Vocab::load(const std::string & path, std::string * err) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        if (err) *err = "vocab unreadable: " + path;
        return false;
    }
    map_.clear();
    pieces_.clear();
    std::string line;
    Token id = 0;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        map_.emplace(line, id++);   // 重复的行保留第一次出现的编号
        pieces_.push_back(line);
    }
    size_ = (size_t)id;
    if (size_ < 8) {
        if (err) *err = "vocab too small: " + std::to_string(size_) + " lines";
        return false;
    }
    return true;
}

const std::string & Vocab::piece(Token id) const {
    static const std::string empty;
    return (id >= 0 && (size_t)id < pieces_.size()) ? pieces_[(size_t)id] : empty;
}

TokenSeq Vocab::encode(const std::string & text) const {
    TokenSeq out;
    const unsigned char * p = (const unsigned char *)text.data();
    const unsigned char * end = p + text.size();
    while (p < end) {
        int len = utf8_len(*p);
        if (p + len > end) len = (int)(end - p);
        auto it = map_.find(std::string((const char *)p, (size_t)len));
        out.push_back(it == map_.end() ? kUnkToken : it->second);
        p += len;
    }
    return out;
}

TokenSeq Vocab::encode_context(const std::string & text, int max_chars) const {
    TokenSeq ids = encode(text);
    if (ids.size() < 2) return {};
    if (max_chars > 0 && (int)ids.size() > max_chars) ids.erase(ids.begin(), ids.end() - max_chars);
    ids.insert(ids.begin(), kBosToken);
    return ids;
}

TokenSeq Vocab::encode_candidate(const std::string & text) const {
    TokenSeq ids = encode(text);
    if (ids.empty()) ids.push_back(kPadToken);
    return ids;
}

}  // namespace suzu
